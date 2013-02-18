/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.tyrus.websockets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.tyrus.websockets.draft06.ClosingFrame;

/**
 * WebSockets engine implementation (singleton), which handles {@link WebSocketApplication}s registration, responsible
 * for client and server handshake validation.
 *
 * @author Alexey Stashok
 * @see WebSocket
 * @see WebSocketApplication
 */
public class WebSocketEngine {
    public static final String SEC_WS_ACCEPT = "Sec-WebSocket-Accept";
    public static final String SEC_WS_KEY_HEADER = "Sec-WebSocket-Key";
    public static final String SEC_WS_ORIGIN_HEADER = "Sec-WebSocket-Origin";
    public static final String ORIGIN_HEADER = "Origin";
    public static final String SEC_WS_PROTOCOL_HEADER = "Sec-WebSocket-Protocol";
    public static final String SEC_WS_EXTENSIONS_HEADER = "Sec-WebSocket-Extensions";
    public static final String SEC_WS_VERSION = "Sec-WebSocket-Version";
    public static final String WEBSOCKET = "websocket";
    public static final String RESPONSE_CODE_MESSAGE = "Switching Protocols";
    public static final int RESPONSE_CODE_VALUE = 101;
    public static final String UPGRADE = "Upgrade";
    public static final String CONNECTION = "Connection";
    public static final String HOST = "Host";
    public static final Version DEFAULT_VERSION = Version.DRAFT17;
    private static final WebSocketEngine engine = new WebSocketEngine();
    private static final Logger LOGGER = Logger.getLogger(WebSocketEngine.WEBSOCKET);
    public static final String SERVER_KEY_HASH = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    public static final int MASK_SIZE = 4;
    private final List<WebSocketApplication> applications = new ArrayList<WebSocketApplication>();

    private final Map<Connection, WebSocketHolder> webSocketHolderMap =

            new TreeMap<Connection, WebSocketHolder>(new Comparator<Connection>() {
                @Override
                public int compare(Connection o1, Connection o2) {
                    if (o1 == null || o2 == null) {
                        if (o1 == null) {
                            if (o2 == null) {
                                return 0;
                            }
                            return 1;
                        } else {
                            return -1;
                        }
                    }

                    if (o1.equals(o2)) {
                        return 0;
                    } else {
                        return o1.hashCode() - o2.hashCode();
                    }
                }
            });

    private WebSocketEngine() {
    }

    public static synchronized WebSocketEngine getEngine() {
        return engine;
    }

    public static byte[] toArray(long length) {
        long value = length;
        byte[] b = new byte[8];
        for (int i = 7; i >= 0 && value > 0; i--) {
            b[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return b;
    }

    public static long toLong(byte[] bytes, int start, int end) {
        long value = 0;
        for (int i = start; i < end; i++) {
            value <<= 8;
            value ^= (long) bytes[i] & 0xFF;
        }
        return value;
    }

    public static List<String> toString(byte[] bytes) {
        return toString(bytes, 0, bytes.length);
    }

    private static List<String> toString(byte[] bytes, int start, int end) {
        List<String> list = new ArrayList<String>();
        for (int i = start; i < end; i++) {
            list.add(Integer.toHexString(bytes[i] & 0xFF).toUpperCase(Locale.US));
        }
        return list;
    }

    WebSocketApplication getApplication(WebSocketRequest request) {
        for (WebSocketApplication application : applications) {
            if (application.upgrade(request)) {
                return application;
            }
        }
        return null;
    }

    /**
     * {@link WebSocketHolder} listener.
     */
    public static abstract class WebSocketHolderListener {
        /**
         * Called when request is ready to upgrade. The responsibility for making {@link org.glassfish.tyrus.websockets.WebSocket#onConnect()}
         * call is on listener when it is used.
         *
         * @param webSocketHolder holder instance.
         * @throws IOException IOException if an I/O error occurred during the upgrade.
         *
         * @see WebSocketHolder#webSocket
         */
        public abstract void onWebSocketHolder(WebSocketHolder webSocketHolder) throws IOException;
    }

    /**
     * Evaluate whether connection/request is suitable for upgrade and perform it.
     *
     * @param connection connection.
     * @param request request.
     * @return {@code true} if upgrade is performed, {@code false} otherwise.
     * @throws IOException if an I/O error occurred during the upgrade.
     */
    public boolean upgrade(Connection connection, WebSocketRequest request) throws IOException {
        return upgrade(connection, request, null);
    }

    /**
     * Evaluate whether connection/request is suitable for upgrade and perform it.
     *
     * @param connection connection.
     * @param request request.
     * @param webSocketHolderListener called when upgrade is going to be performed. Additinally, leaves
     *                                {@link org.glassfish.tyrus.websockets.WebSocket#onConnect()} call
     *                                responsibility to {@link WebSocketHolderListener} instance.
     * @return {@code true} if upgrade is performed, {@code false} otherwise.
     * @throws IOException if an I/O error occurred during the upgrade.
     */
    public boolean upgrade(Connection connection, WebSocketRequest request, WebSocketHolderListener webSocketHolderListener) throws IOException {
        final WebSocketApplication app = WebSocketEngine.getEngine().getApplication(request);
        WebSocket socket = null;
        try {
            if (app != null) {
                final ProtocolHandler protocolHandler = loadHandler(request.getHeaders());
                if (protocolHandler == null) {
                    handleUnsupportedVersion(connection, request);
                    return false;
                }
                protocolHandler.setConnection(connection);
                socket = app.createSocket(protocolHandler, request, app);
                WebSocketHolder holder =
                        WebSocketEngine.getEngine().setWebSocketHolder(connection, protocolHandler, socket);
                holder.application = app;
                protocolHandler.handshake(connection, app, request);
                request.getConnection().addCloseListener(new Connection.CloseListener() {
                    @Override
                    public void onClose(final Connection connection/*, final CloseType type*/) throws IOException {

                        final WebSocket webSocket = getWebSocket(connection);
                        if (webSocket != null) {
                            webSocket.close();
                            webSocket.onClose(new ClosingFrame(WebSocket.END_POINT_GOING_DOWN,
                                    "Close detected on connection"));
                        }
                    }
                });

                if(webSocketHolderListener != null) {
                    webSocketHolderListener.onWebSocketHolder(holder);
                } else {
                    socket.onConnect();
                }
                return true;
            }
        } catch (HandshakeException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            if (socket != null) {
                socket.close();
            }
        }
        return false;
    }

    private static ProtocolHandler loadHandler(Map<String, String> headers) {
        for (Version version : Version.values()) {
            if (version.validate(headers)) {
                return version.createHandler(false);
            }
        }
        return null;
    }

    /**
     * Registers the specified {@link WebSocketApplication} with the
     * <code>WebSocketEngine</code>.
     *
     * @param app the {@link WebSocketApplication} to register.
     */
    public void register(WebSocketApplication app) {
        applications.add(app);
    }

    /**
     * Un-registers the specified {@link WebSocketApplication} with the
     * <code>WebSocketEngine</code>.
     *
     * @param app the {@link WebSocketApplication} to un-register.
     */
    public void unregister(WebSocketApplication app) {
        applications.remove(app);
    }

    /**
     * Un-registers all {@link WebSocketApplication} instances with the
     * {@link WebSocketEngine}.
     */
    public void unregisterAll() {
        applications.clear();
    }

    /**
     * Returns <tt>true</tt> if passed Grizzly {@link Connection} is associated with a {@link WebSocket}, or
     * <tt>false</tt> otherwise.
     *
     * @param connection Grizzly {@link Connection}.
     * @return <tt>true</tt> if passed Grizzly {@link Connection} is associated with a {@link WebSocket}, or
     *         <tt>false</tt> otherwise.
     */
    public boolean webSocketInProgress(Connection connection) {
        synchronized(webSocketHolderMap){
            return webSocketHolderMap.get(connection) != null;
        }
    }

    /**
     * Get the {@link WebSocket} associated with the Grizzly {@link Connection}, or <tt>null</tt>, if there none is
     * associated.
     *
     * @param connection Grizzly {@link Connection}.
     * @return the {@link WebSocket} associated with the Grizzly {@link Connection}, or <tt>null</tt>, if there none is
     *         associated.
     */
    public WebSocket getWebSocket(Connection connection) {
        final WebSocketHolder holder = getWebSocketHolder(connection);
        return holder == null ? null : holder.webSocket;
    }

    public WebSocketHolder getWebSocketHolder(final Connection connection) {
        synchronized(webSocketHolderMap){
            return webSocketHolderMap.get(connection);
        }
    }

    public WebSocketHolder setWebSocketHolder(final Connection connection, ProtocolHandler handler, WebSocket socket) {
        final WebSocketHolder holder = new WebSocketHolder(handler, socket);
        synchronized(webSocketHolderMap){
            webSocketHolderMap.put(connection, holder);
        }

        return holder;
    }

    private static void handleUnsupportedVersion(final Connection connection,
                                                 final WebSocketRequest request) {
        WebSocketResponse response = new WebSocketResponse();
        response.setStatus(400);
        response.getHeaders().put(WebSocketEngine.SEC_WS_VERSION, Version.getSupportedWireProtocolVersions());
        connection.write(response);
    }

    /**
     * WebSocketHolder object, which gets associated with the Grizzly {@link Connection}.
     */
    public final static class WebSocketHolder {
        public volatile WebSocket webSocket;
        public volatile HandShake handshake;
        public volatile WebSocketApplication application;
        public volatile ByteBuffer buffer;
        public volatile ProtocolHandler handler;

        WebSocketHolder(final ProtocolHandler handler, final WebSocket socket) {
            this.handler = handler;
            this.webSocket = socket;
        }
    }
}
