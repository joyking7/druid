/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.data.input.impl;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Throwables;
import com.google.common.io.CountingInputStream;
import org.apache.druid.data.input.impl.prefetch.Fetcher;
import org.apache.druid.data.input.impl.prefetch.ObjectOpenFunction;
import org.apache.druid.java.util.common.RetryUtils;
import org.apache.druid.java.util.common.logger.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;

/**
 * This class is used by {@link Fetcher} when prefetch is disabled. It's responsible for re-opening the underlying input
 * stream for the input object on the socket connection reset as well as the given {@link #retryCondition}.
 *
 * @param <T> object type
 */
public class RetryingInputStream<T> extends InputStream
{

  public static final Predicate<Throwable> DEFAULT_RETRY_CONDITION = Predicates.alwaysFalse();
  public static final Predicate<Throwable> DEFAULT_RESET_CONDITION = RetryingInputStream::isConnectionReset;

  private static final Logger log = new Logger(RetryingInputStream.class);

  private final T object;
  private final ObjectOpenFunction<T> objectOpenFunction;
  private final Predicate<Throwable> retryCondition;
  private final Predicate<Throwable> resetCondition;
  private final int maxRetry;

  private CountingInputStream delegate;
  private long startOffset;

  /**
   *
   * @param object The object entity to open
   * @param objectOpenFunction How to open the object
   * @param retryCondition A predicate on a throwable to indicate if stream should retry. This defaults to
   *                       {@link IOException}, not retryable, when null is passed
   * @param resetCondition A predicate on a throwable to indicate if stream should reset. This defaults to
   *                       a generic reset test, see {@link #isConnectionReset(Throwable)} when null is passed
   * @param maxRetry      The maximum times to retry. Defaults to {@link RetryUtils#DEFAULT_MAX_TRIES} when null
   * @throws IOException
   */
  public RetryingInputStream(
      T object,
      ObjectOpenFunction<T> objectOpenFunction,
      @Nullable Predicate<Throwable> retryCondition,
      @Nullable Predicate<Throwable> resetCondition,
      @Nullable Integer maxRetry
  ) throws IOException
  {
    this.object = object;
    this.objectOpenFunction = objectOpenFunction;
    this.retryCondition = retryCondition == null ? DEFAULT_RETRY_CONDITION : retryCondition;
    this.resetCondition = resetCondition == null ? DEFAULT_RESET_CONDITION : resetCondition;
    this.maxRetry = maxRetry == null ? RetryUtils.DEFAULT_MAX_TRIES : maxRetry;
    this.delegate = new CountingInputStream(objectOpenFunction.open(object));
  }

  private static boolean isConnectionReset(Throwable t)
  {
    return (t instanceof SocketException && (t.getMessage() != null && t.getMessage().contains("Connection reset"))) ||
           (t.getCause() != null && isConnectionReset(t.getCause()));
  }

  private void waitOrThrow(Throwable t, int nTry) throws IOException
  {
    final boolean isConnectionReset = resetCondition.apply(t);
    if (isConnectionReset || retryCondition.apply(t)) {
      if (isConnectionReset) {
        // Re-open the input stream on connection reset
        startOffset += delegate.getCount();
        try {
          delegate.close();
        }
        catch (IOException e) {
          // ignore this exception
          log.warn(e, "Error while closing the delegate input stream");
        }
      }
      try {
        // Wait for the next try
        RetryUtils.awaitNextRetry(t, null, nTry + 1, maxRetry, false);

        if (isConnectionReset) {
          log.info("retrying from offset[%d]", startOffset);
          delegate = new CountingInputStream(objectOpenFunction.open(object, startOffset));
        }
      }
      catch (InterruptedException | IOException e) {
        t.addSuppressed(e);
        throwAsIOException(t);
      }
    } else {
      throwAsIOException(t);
    }
  }

  private static void throwAsIOException(Throwable t) throws IOException
  {
    Throwables.propagateIfInstanceOf(t, IOException.class);
    throw new IOException(t);
  }

  @Override
  public int read() throws IOException
  {
    for (int nTry = 0; nTry < maxRetry; nTry++) {
      try {
        return delegate.read();
      }
      catch (Throwable t) {
        waitOrThrow(t, nTry);
      }
    }
    return delegate.read();
  }

  @Override
  public int read(byte[] b) throws IOException
  {
    for (int nTry = 0; nTry < maxRetry; nTry++) {
      try {
        return delegate.read(b);
      }
      catch (Throwable t) {
        waitOrThrow(t, nTry);
      }
    }
    return delegate.read(b);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException
  {
    for (int nTry = 0; nTry < maxRetry; nTry++) {
      try {
        return delegate.read(b, off, len);
      }
      catch (Throwable t) {
        waitOrThrow(t, nTry);
      }
    }
    return delegate.read(b, off, len);
  }

  @Override
  public long skip(long n) throws IOException
  {
    for (int nTry = 0; nTry < maxRetry; nTry++) {
      try {
        return delegate.skip(n);
      }
      catch (Throwable t) {
        waitOrThrow(t, nTry);
      }
    }
    return delegate.skip(n);
  }

  @Override
  public int available() throws IOException
  {
    for (int nTry = 0; nTry < maxRetry; nTry++) {
      try {
        return delegate.available();
      }
      catch (Throwable t) {
        waitOrThrow(t, nTry);
      }
    }
    return delegate.available();
  }

  @Override
  public void close() throws IOException
  {
    delegate.close();
  }
}
