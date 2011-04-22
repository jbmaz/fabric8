/**
 * Copyright (C) 2011, FuseSource Corp.  All rights reserved.
 * http://fusesource.com
 *
 * The software in this package is published under the terms of the
 * CDDL license a copy of which has been included with this distribution
 * in the license.txt file.
 */
package org.fusesource.fabric.dosgi.tcp;

import org.fusesource.fabric.dosgi.api.AsyncCallback;
import org.fusesource.fabric.dosgi.api.SerializationStrategy;
import org.fusesource.hawtbuf.DataByteArrayInputStream;
import org.fusesource.hawtbuf.DataByteArrayOutputStream;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.rmi.RemoteException;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/**
 * <p>
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class BlockingInvocationStrategy implements InvocationStrategy {

    public static final BlockingInvocationStrategy INSTANCE = new BlockingInvocationStrategy();

    private static final Callable<Object> EMPTY_CALLABLE = new Callable<Object>() {
        public Object call() {
            return null;
        }
    };

    private class BlockingResponseFuture extends FutureTask<Object> implements ResponseFuture, AsyncCallback {

        private final ClassLoader loader;
        private final Method method;
        private final SerializationStrategy serializationStrategy;

        public BlockingResponseFuture(ClassLoader loader, Method method, SerializationStrategy serializationStrategy) {
            super(EMPTY_CALLABLE);
            this.loader = loader;
            this.method = method;
            this.serializationStrategy = serializationStrategy;
        }

        public void set(DataByteArrayInputStream source) throws IOException, ClassNotFoundException {
            try {
                serializationStrategy.decodeResponse(loader, method.getReturnType(), source, this);
            } catch (Throwable e) {
                super.setException(e);
            }
        }

        public void onSuccess(Object result) {
            super.set(result);
        }

        public void onFailure(Throwable failure) {
            super.setException(failure);
        }
    }

    public ResponseFuture request(SerializationStrategy serializationStrategy, ClassLoader loader, Method method, Object[] args, DataByteArrayOutputStream target) throws Exception {
        serializationStrategy.encodeRequest(loader, method.getParameterTypes(), args, target);
        return new BlockingResponseFuture(loader, method, serializationStrategy);
    }

    public void service(SerializationStrategy serializationStrategy, ClassLoader loader, Method method, Object target, DataByteArrayInputStream requestStream, DataByteArrayOutputStream responseStream, Runnable onComplete) {

        int pos = responseStream.position();
        try {

            Object value = null;
            Throwable error = null;

            try {
                Class<?>[] types = method.getParameterTypes();
                final Object[] args = new Object[types.length];
                serializationStrategy.decodeRequest(loader, types, requestStream, args);
                value = method.invoke(target, args);
            } catch (Throwable t) {
                if (t instanceof InvocationTargetException) {
                    error = t.getCause();
                } else {
                    error = t;
                }
            }

            serializationStrategy.encodeResponse(loader, method.getReturnType(), value, error, responseStream);

        } catch(Exception e) {

            // we failed to encode the response.. reposition and write that error.
            try {
                responseStream.position(pos);
                serializationStrategy.encodeResponse(loader, method.getReturnType(), null, new RemoteException(e.toString()), responseStream);
            } catch (Exception unexpected) {
                unexpected.printStackTrace();
            }

        } finally {
            onComplete.run();
        }
    }

}
