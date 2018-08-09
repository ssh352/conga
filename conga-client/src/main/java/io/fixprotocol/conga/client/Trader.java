/*
 * Copyright 2018 FIX Protocol Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package io.fixprotocol.conga.client;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Timer;
import java.util.UUID;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.fixprotocol.conga.buffer.BufferPool;
import io.fixprotocol.conga.buffer.BufferSupplier;
import io.fixprotocol.conga.buffer.RingBufferSupplier;
import io.fixprotocol.conga.client.io.ClientEndpoint;
import io.fixprotocol.conga.client.session.ClientSession;
import io.fixprotocol.conga.messages.appl.ApplicationMessageConsumer;
import io.fixprotocol.conga.messages.appl.Message;
import io.fixprotocol.conga.messages.appl.MessageException;
import io.fixprotocol.conga.messages.appl.MutableMessage;
import io.fixprotocol.conga.messages.appl.MutableNewOrderSingle;
import io.fixprotocol.conga.messages.appl.MutableOrderCancelRequest;
import io.fixprotocol.conga.messages.appl.MutableRequestMessageFactory;
import io.fixprotocol.conga.messages.appl.ResponseMessageFactory;
import io.fixprotocol.conga.messages.session.SessionMessenger;
import io.fixprotocol.conga.messages.spi.MessageProvider;
import io.fixprotocol.conga.session.Session;
import io.fixprotocol.conga.session.SessionEvent;
import io.fixprotocol.conga.session.SessionMessageConsumer;
import io.fixprotocol.conga.session.SessionState;

/**
 * Trader application sends orders and cancels to Exchange and receives executions
 * <p>
 * Assumption: Trader has 1:1 relationship with session and transport instances.
 * <p>
 * <b>Session/transport layer:</b> WebSocket client over TLS. To configure a trust store for TLS,
 * set these environment variables:
 * 
 * <pre>
 * -Djavax.net.ssl.trustStore=client.pkcs -Djavax.net.ssl.trustStorePassword=storepassword
 * </pre>
 * 
 * <b>Presentation layer:</b> the initial implementation encodes messages using Simple Binary
 * Encoding (SBE)
 * 
 * @author Don Mendelson
 *
 */
public class Trader implements AutoCloseable {

  /**
   * Builds an instance of {@code Trader}
   * <p>
   * Example:
   * 
   * <pre>
   * Trader trader = Trader.builder().host("1.2.3.4").port("567").build();
   * </pre>
   *
   */
  public static class Builder {

    private static final String WEBSOCKET_SCHEME = "wss";

    private static URI createUri(String host, int port, String path) throws URISyntaxException {
      return new URI(WEBSOCKET_SCHEME, null, host, port, path, null, null);
    }

    private String encoding;
    private Consumer<Throwable> errorListener = Throwable::printStackTrace;
    private String host = DEFAULT_HOST;
    private ApplicationMessageConsumer messageListener = null;
    private String path = DEFAULT_PATH;
    private int port = DEFAULT_PORT;
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private URI uri = null;

    protected Builder() {

    }

    public Trader build() throws URISyntaxException {
      if (null == this.uri) {
        this.uri = createUri(host, port, path);
      }

      return new Trader(this);
    }

    public Builder encoding(String encoding) {
      this.encoding = Objects.requireNonNull(encoding);
      return this;
    }

    public Builder errorListener(Consumer<Throwable> errorListener) {
      this.errorListener = Objects.requireNonNull(errorListener);
      return this;
    }

    public Builder host(String host) {
      this.host = Objects.requireNonNull(host);
      return this;
    }

    public Builder messageListener(ApplicationMessageConsumer messageListener) {
      this.messageListener = Objects.requireNonNull(messageListener);
      return this;
    }

    public Builder path(String path) {
      this.path = Objects.requireNonNull(path);
      return this;
    }

    public Builder port(int port) {
      this.port = port;
      return this;
    }

    public Builder timeoutSeconds(int timeoutSeconds) {
      this.timeoutSeconds = timeoutSeconds;
      return this;
    }

    public Builder uri(URI uri) {
      this.uri = Objects.requireNonNull(uri);
      return this;
    }

  }

  public static final String DEFAULT_HOST = "localhost";
  public static final String DEFAULT_PATH = "/trade";
  public static final int DEFAULT_PORT = 443;
  public static final int DEFAULT_TIMEOUT_SECONDS = 30;

  public static Builder builder() {
    return new Builder();
  }

  private final BufferSupplier bufferSupplier = new BufferPool();
  private final ClientEndpoint endpoint;
  private final Consumer<Throwable> errorListener;
  private final Subscriber<? super SessionEvent> eventSubscriber = new Subscriber<>() {


    @Override
    public void onComplete() {
      System.out.println("Session events complete");
    }

    @Override
    public void onError(Throwable throwable) {
      errorListener.accept(throwable);
    }

    @Override
    public void onNext(SessionEvent item) {
      try {
        lock.lock();
        sessionStateCondition.signalAll();
        switch (item.getState()) {
          case ESTABLISHED:
            System.out.println("Session established");
            break;
          case NEGOTIATED:
            System.out.println("Session negotiated");
            break;
          case FINALIZED:
            System.out.println("Session finalized");
            break;
          case NOT_ESTABLISHED:
            System.out.println("Session transport unbound");
            break;
          case NOT_NEGOTIATED:
            System.out.println("Session initialized");
            break;
        }
        request(1);
      } finally {
        lock.unlock();
      }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      Trader.this.subscription = subscription;
      request(1);
    }

    private void request(int n) {
      subscription.request(n);
    }
  };

  private final ReentrantLock lock = new ReentrantLock();
  private ApplicationMessageConsumer messageListener = null;
  private final MutableRequestMessageFactory requestFactory;
  private final ResponseMessageFactory responseFactory;
  private final RingBufferSupplier ringBuffer;
  private ClientSession session;
  private final SessionMessenger sessionMessenger;
  private final Condition sessionStateCondition;
  private Subscription subscription;
  private final int timeoutSeconds;
  private final Timer timer = new Timer("Client-timer", true);
  
  // Consumes application messages from Session
  private final SessionMessageConsumer sessionMessageConsumer = (source, buffer, seqNo) -> {
    Message message;
    try {
      message = getResponseFactory().wrap(buffer);
      messageListener.accept(source, message, seqNo);
    } catch (MessageException e) {
      getErrorListener().accept(e);
    }

  };
  
  // Consumes messages from ring buffer
  private final BiConsumer<String, ByteBuffer> incomingMessageConsumer = (source, buffer) -> {
    try {
      session.messageReceived(buffer);
    } catch (Exception e) {
      if (getErrorListener() != null) {
        getErrorListener().accept(e);
      }
      session.disconnected();
    }
  };

  /**
   * Invoke application to test connectivity
   * @param args
   * @throws Exception
   */
  public static void main(String[] args) throws Exception {
    // Use communication defaults and SBE encoding by default
    String encoding = "SBE";
    
    if (args.length > 0) {
      encoding = args[0];
    }
    
    try (Trader trader = Trader.builder().host("localhost").port(8025).path("/trade")
        .encoding(encoding).messageListener(new ApplicationMessageConsumer() {

          @Override
          public void accept(String source, Message message, long seqNo) {

          }}).build()) {
      trader.open();
      final Object monitor = new Object();

      new Thread(() -> {

        boolean running = true;
        while (running) {
          synchronized (monitor) {
            try {
              monitor.wait();
            } catch (InterruptedException e) {
              running = false;
            }
          }
        }
        trader.close();
      });
    }
  }
  private Trader(Builder builder) {
    this.messageListener =
        Objects.requireNonNull(builder.messageListener, "Message listener not set");
    this.errorListener = builder.errorListener;
    this.timeoutSeconds = builder.timeoutSeconds;
    this.ringBuffer = new RingBufferSupplier(incomingMessageConsumer);
    this.endpoint = new ClientEndpoint(ringBuffer, builder.uri, builder.timeoutSeconds);
    MessageProvider messageProvider = provider(builder.encoding);
    this.requestFactory = messageProvider.getMutableRequestMessageFactory(bufferSupplier);
    this.responseFactory = messageProvider.getResponseMessageFactory();
    this.sessionMessenger = messageProvider.getSessionMessenger();
    sessionStateCondition = lock.newCondition();
  }


  public void close() {
    try {
      if (session != null) {
        try {
          lock.lockInterruptibly();
          session.finalizeFlow();
          while (session.getSessionState() != SessionState.FINALIZED) {
            sessionStateCondition.await(timeoutSeconds, TimeUnit.SECONDS);
          }
        } finally {
          lock.unlock();
        }
      }
      ringBuffer.stop();
    } catch (Exception e) {
      errorListener.accept(e);
    }
  }

  /**
   * Returns an order message encoder
   * 
   * The encoder is thread-safe. That is, messages may be created on multiple threads concurrently.
   * However, a message must be populated on the same thread that invoked the create method.
   * 
   * @return a mutable order message
   */
  public MutableNewOrderSingle createOrder() {
    return requestFactory.getNewOrderSingle();
  }

  /**
   * Returns an order cancel message encoder
   * 
   * The encoder is thread-safe. That is, messages may be created on multiple threads concurrently.
   * However, a message must be populated on the same thread that invoked the create method.
   * 
   * @return a mutable order cancel message
   */
  public MutableOrderCancelRequest createOrderCancelRequest() {
    return requestFactory.getOrderCancelRequest();
  }

  /**
   * Opens a session with a server
   * <p>
   * A subscription for session events
   * 
   * @throws Exception if a session cannot be established
   */
  public void open() throws Exception {
    ringBuffer.start();
    if (session == null) {
      UUID uuid = UUID.randomUUID();
      this.session = ClientSession.builder().sessionId(Session.UUIDAsBytes(uuid)).timer(timer)
          .heartbeatInterval(TimeUnit.SECONDS.toMillis(timeoutSeconds))
          .sessionMessageConsumer(sessionMessageConsumer).sessionMessenger(sessionMessenger)
          .build();
      session.subscribeForEvents(eventSubscriber);

    }
    endpoint.open();
    session.connected(endpoint, endpoint.getSource());
  }

  /**
   * Send an order or cancel request
   * 
   * @param message
   * @return sequence number of the sent message
   * @throws TimeoutException if the operation fails to complete in a timeout period
   * @throws InterruptedException if the current thread is interrupted
   * @throws IOException if an I/O error occurs
   */
  public long send(MutableMessage message) throws IOException, InterruptedException {
    Objects.requireNonNull(message);
    try {
      lock.lockInterruptibly();
      while (session.getSessionState() != SessionState.ESTABLISHED) {
        sessionStateCondition.await(timeoutSeconds, TimeUnit.SECONDS);
      }
      return session.sendApplicationMessage(message.toBuffer());
    } finally {
      lock.unlock();
      message.release();
    }
  }

  public void suspend() {
    try {
      if (session != null) {
        try {
          lock.lockInterruptibly();
          endpoint.close();
          while (session.getSessionState() != SessionState.NOT_ESTABLISHED) {
            sessionStateCondition.await(timeoutSeconds, TimeUnit.SECONDS);
          }
        } finally {
          lock.unlock();
        }
      }
    } catch (Exception e) {
      errorListener.accept(e);
    }
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("Trader [session=").append(session).append(", endpoint=").append(endpoint)
        .append(", timeoutSeconds=").append(timeoutSeconds).append("]");
    return builder.toString();
  }

  private ResponseMessageFactory getResponseFactory() {
    return responseFactory;
  }


  /**
   * Locate a service provider for an application message encoding
   * 
   * @param name encoding name
   * @return a service provider
   */
  private MessageProvider provider(String name) {
    ServiceLoader<MessageProvider> loader = ServiceLoader.load(MessageProvider.class);
    for (MessageProvider provider : loader) {
      if (provider.name().equals(name)) {
        return provider;
      }
    }
    throw new RuntimeException("No MessageProvider found");
  }

  void cancelEventSubscription() {
    if (subscription != null) {
      subscription.cancel();
    }
  }

  Consumer<Throwable> getErrorListener() {
    return errorListener;
  }
}
