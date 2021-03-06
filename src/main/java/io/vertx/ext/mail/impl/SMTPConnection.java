/*
 *  Copyright (c) 2011-2015 The original author or authors
 *
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  and Apache License v2.0 which accompanies this distribution.
 *
 *       The Eclipse Public License is available at
 *       http://www.eclipse.org/legal/epl-v10.html
 *
 *       The Apache License v2.0 is available at
 *       http://www.opensource.org/licenses/apache2.0.php
 *
 *  You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.mail.impl;

import io.vertx.core.*;
import io.vertx.core.impl.NoStackTraceThrowable;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.mail.MailConfig;

import java.util.List;

/**
 * SMTP connection to a server.
 * <p>
 * Encapsulate the NetSocket connection and the data writing/reading
 *
 * @author <a href="http://oss.lehmann.cx/">Alexander Lehmann</a>
 */
class SMTPConnection {

  private static final Logger log = LoggerFactory.getLogger(SMTPConnection.class);

  private NetSocket ns;
  private boolean socketClosed;
  private boolean socketShutDown;
  private Handler<String> commandReplyHandler;
  private Handler<Throwable> errorHandler;
  private boolean broken;
  private boolean idle;
  private boolean doShutdown;
  private final NetClient client;
  private Capabilities capa = new Capabilities();
  private final ConnectionLifeCycleListener listener;
  private Context context;
  private final MultilineParser nsHandler;

  SMTPConnection(NetClient client, ConnectionLifeCycleListener listener) {
    broken = true;
    idle = false;
    doShutdown = false;
    socketClosed = false;
    socketShutDown = false;
    this.client = client;
    this.listener = listener;
    this.nsHandler = new MultilineParser(buffer -> {
      if (commandReplyHandler == null) {
        log.debug("dropping reply arriving after we stopped processing the buffer.");
      } else {
        // make sure we only call the handler once
        Handler<String> currentHandler = commandReplyHandler;
        commandReplyHandler = null;
        currentHandler.handle(buffer.toString());
      }
    });
  }

  /**
   * @return the capabilities object
   */
  Capabilities getCapa() {
    return capa;
  }

  /**
   * parse capabilities from the ehlo reply string
   *
   * @param message the capabilities to set
   */
  void parseCapabilities(String message) {
    capa = new Capabilities();
    capa.parseCapabilities(message);
  }

  void shutdown() {
    broken = true;
    commandReplyHandler = null;
    socketShutDown = true;
    if (ns != null) {
      ns.close();
      ns = null;
    }
  }

  void writeCommands(List<String> commands, Handler<String> resultHandler) {
    String cmds = String.join("\r\n", commands);
    this.nsHandler.setExpected(commands.size());
    this.write(cmds, r -> {
      try {
        resultHandler.handle(r);
      } finally {
        this.nsHandler.setExpected(1);
      }
    });
  }

  /*
   * write command without masking anything
   */
  void write(String str, Handler<String> commandResultHandler) {
    write(str, -1, commandResultHandler);
  }

  /*
   * write command masking everything after position blank
   */
  void write(String str, int blank, Handler<String> commandResultHandler) {
    this.commandReplyHandler = commandResultHandler;
    if (socketClosed) {
      log.debug("connection was closed by server");
      handleError("connection was closed by server");
    } else {
      if (ns != null) {
        if (log.isDebugEnabled()) {
          String logStr;
          if (blank >= 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = blank; i < str.length(); i++) {
              sb.append('*');
            }
            logStr = str.substring(0, blank) + sb;
          } else {
            logStr = str;
          }
          // avoid logging large mail body
          if (logStr.length() < 1000) {
            log.debug("command: " + logStr);
          } else {
            log.debug("command: " + logStr.substring(0, 1000) + "...");
          }
        }
        ns.write(str + "\r\n");
      } else {
        log.debug("not sending command " + str + " since the netsocket is null");
      }
    }
  }

  // write single line not expecting a reply
  void writeLine(String str, boolean mayLog) {
    if (mayLog) {
      log.debug(str);
    }
    ns.write(str + "\r\n");
  }

  // write single line not expecting a reply, using drain handler
  void writeLineWithDrainPromise(String str, boolean mayLog, Promise<Void> promise) {
    if (mayLog) {
      log.debug(str);
    }
    if (ns.writeQueueFull()) {
      ns.drainHandler(v -> {
        // avoid getting confused by being called twice
        ns.drainHandler(null);
        ns.write(str + "\r\n").onComplete(promise);
      });
    } else {
      ns.write(str + "\r\n").onComplete(promise);
    }
  }

  private void handleError(String message) {
    handleError(new NoStackTraceThrowable(message));
  }

  private void handleError(Throwable throwable) {
    errorHandler.handle(throwable);
  }

  public void openConnection(MailConfig config, Handler<String> initialReplyHandler, Handler<Throwable> errorHandler) {
    this.errorHandler = errorHandler;
    broken = false;
    idle = false;

    client.connect(config.getPort(), config.getHostname(), asyncResult -> {
      if (asyncResult.succeeded()) {
        context = Vertx.currentContext();
        ns = asyncResult.result();
        socketClosed = false;
        ns.exceptionHandler(e -> {
          // avoid returning two exceptions
          log.debug("exceptionHandler called");
          if (!socketClosed && !socketShutDown && !idle && !broken) {
            setBroken();
            log.debug("got an exception on the netsocket", e);
            handleError(e);
          } else {
            log.debug("not returning follow-up exception", e);
          }
        });
        ns.closeHandler(v -> {
          log.debug("socket has been closed");
          listener.connectionClosed(this);
          socketClosed = true;
          // avoid exception if we regularly shut down the socket on our side
          if (!socketShutDown && !idle && !broken) {
            setBroken();
            log.debug("throwing: connection has been closed by the server");
            handleError("connection has been closed by the server");
          } else {
            if (socketShutDown || broken) {
              log.debug("close has been expected");
            } else {
              log.debug("closed while connection has been idle (timeout on server?)");
            }
            if (!broken) {
              setBroken();
            }
            if (!socketShutDown) {
              shutdown();
              listener.dataEnded(this);
            }
          }
        });
        commandReplyHandler = initialReplyHandler;
        ns.handler(this.nsHandler);
      } else {
        log.error("exception on connect", asyncResult.cause());
        // notify the pool that the connection attempt didn't work so that the connection count is correct
        listener.connectionClosed(null);
        handleError(asyncResult.cause());
      }
    });
  }

  boolean isSsl() {
    return ns.isSsl();
  }

  void upgradeToSsl(Handler<AsyncResult<Void>> handler) {
    ns.upgradeToSsl(handler);
  }

  public boolean isBroken() {
    return broken;
  }

  public boolean isIdle() {
    return idle;
  }

  public void returnToPool() {
    if (isIdle()) {
      log.info("state error: idle connection returned to pool");
      handleError("state error: idle connection returned to pool");
    } else {
      if (doShutdown) {
        log.debug("shutting connection down");
        quitCloseConnection();
      } else {
        log.debug("returning connection to pool");
        commandReplyHandler = null;
        listener.dataEnded(this);
        log.debug("setting error handler to null");
        errorHandler = null;
      }
    }
  }

  /**
   * send QUIT and close the connection, this operation waits for the success of the quit command but will close the
   * connection on exception as well
   */
  private void quitCloseConnection() {
    if (!socketShutDown) {
      context.runOnContext(v1 -> {
        log.debug("shutting down connection");
        if (socketClosed) {
          log.debug("connection is already closed, only doing shutdown()");
          shutdown();
        } else {
          // set the connection to in use to avoid it being used by another getConnection operation
          useConnection();
          new SMTPQuit(this, v -> {
            shutdown();
            log.debug("connection is shut down");
          }).start();
        }
      });
    }
  }

  /**
   * mark a connection as being used again
   */
  void useConnection() {
    idle = false;
  }

  /**
   * mark a connection as free
   */
  void setIdle() {
    idle = true;
  }

  /**
   * set error handler to a "local" handler to be reset later
   */
  private Handler<Throwable> prevErrorHandler = null;

  public void setErrorHandler(Handler<Throwable> newHandler) {
    if (prevErrorHandler == null) {
      prevErrorHandler = errorHandler;
    }

    errorHandler = newHandler;
  }

  /**
   * reset error handler to previous
   */
  public void resetErrorHandler() {
    errorHandler = prevErrorHandler;
  }

  /**
   * set connection to broken and shut it down
   */
  public void setBroken() {
    if (!broken) {
      log.debug("setting connection to broken");
      broken = true;
      commandReplyHandler = null;
      log.debug("closing connection");
      shutdown();
      listener.dataEnded(this);
    } else {
      log.debug("connection is already set to broken");
    }
  }

  /**
   * if connection is still active, shut it down when the current
   * operation has finished
   */
  public void setDoShutdown() {
    log.debug("will shut down connection after send operation finishes");
    doShutdown = true;
  }

  /**
   * close the connection doing a QUIT command first
   */
  public void close() {
    quitCloseConnection();
  }

  /**
   * check if a connection is already closed (this is mostly for unit tests)
   */
  boolean isClosed() {
    return socketClosed;
  }

  /**
   * get the context associated with this connection
   *
   * @return
   */
  Context getContext() {
    return context;
  }

  /**
   * Gets the underline NetSocket to the email server.
   *
   * @return the underline NetSocket
   */
  NetSocket getSocket() {
    return ns;
  }

}
