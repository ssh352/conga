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

package io.fixprotocol.conga.sbe.messages.appl;

import io.fixprotocol.conga.buffer.BufferSupplier;
import io.fixprotocol.conga.messages.appl.MutableNewOrderSingle;
import io.fixprotocol.conga.messages.appl.MutableOrderCancelRequest;
import io.fixprotocol.conga.messages.appl.MutableRequestMessageFactory;

/**
 * @author Don Mendelson
 *
 */
public class SbeMutableRequestMessageFactory implements MutableRequestMessageFactory {

  private final BufferSupplier bufferSupplier;

  private final ThreadLocal<SbeMutableOrderCancelRequest> cancelRequestThreadLocal =
      ThreadLocal.withInitial(SbeMutableOrderCancelRequest::new);

  private final ThreadLocal<SbeMutableNewOrderSingle> newOrderSingleThreadLocal =
      ThreadLocal.withInitial(SbeMutableNewOrderSingle::new);


  /**
   * Constructor
   * @param bufferSupplier supplies buffers to encode messages
   */
  public SbeMutableRequestMessageFactory(BufferSupplier bufferSupplier) {
    this.bufferSupplier = bufferSupplier;
  }

  @Override
  public MutableNewOrderSingle getNewOrderSingle() {
    return newOrderSingleThreadLocal.get().wrap(bufferSupplier);
  }

  @Override
  public MutableOrderCancelRequest getOrderCancelRequest() {
    return cancelRequestThreadLocal.get().wrap(bufferSupplier);
  }

}
