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
package org.apache.sshd.agent;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.service.IoAcceptor;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.apache.sshd.SshAgent;
import org.apache.sshd.common.KeyPairProvider;
import org.apache.sshd.common.util.Buffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * A server for an SSH Agent
 */
public class AgentServer implements SshAgent {

    static final byte SSH_AGENT_SUCCESS = 6;
    static final byte SSH2_AGENTC_REQUEST_IDENTITIES = 11;
    static final byte SSH2_AGENT_IDENTITIES_ANSWER = 12;
    static final byte SSH2_AGENTC_SIGN_REQUEST = 13;
    static final byte SSH2_AGENT_SIGN_RESPONSE = 14;
    static final byte SSH2_AGENTC_ADD_IDENTITY = 17;
    static final byte SSH2_AGENTC_REMOVE_IDENTITY = 18;
    static final byte SSH2_AGENTC_REMOVE_ALL_IDENTITIES = 19;
    static final byte SSH2_AGENT_FAILURE = 30;

    private final SshAgent engine = new AgentLocal();
    private IoAcceptor acceptor;
    private SocketAddress address;


    public String start() throws Exception {
        acceptor = new NioSocketAcceptor();
        acceptor.setHandler(new IoHandlerAdapter() {
            @Override
            public void sessionCreated(IoSession session) throws Exception {
                SshAgentSession s = new SshAgentSession(session, engine);
                session.setAttribute(SshAgentSession.class, s);
            }
            @Override
            public void messageReceived(IoSession session, Object message) throws Exception {
                SshAgentSession s = (SshAgentSession) session.getAttribute(SshAgentSession.class);
                s.messageReceived(message);
            }
        });
        acceptor.bind(new InetSocketAddress("localhost", 0));
        address = acceptor.getLocalAddress();
        return Integer.toString(((InetSocketAddress) address).getPort());
    }

    public void close() {
        engine.close();
        acceptor.dispose();
    }

    public List<Pair<PublicKey, String>> getIdentities() throws IOException {
        return engine.getIdentities();
    }

    public byte[] sign(PublicKey key, byte[] data) throws IOException {
        return engine.sign(key, data);
    }

    public void addIdentity(KeyPair key, String comment) throws IOException {
        engine.addIdentity(key, comment);
    }

    public void removeIdentity(PublicKey key) throws IOException {
        engine.removeIdentity(key);
    }

    public void removeAllIdentities() throws IOException {
        engine.removeAllIdentities();
    }

    protected static class SshAgentSession {

        private final IoSession session;
        private final SshAgent engine;
        private final Buffer buffer = new Buffer();

        public SshAgentSession(IoSession session, SshAgent engine) {
            this.session = session;
            this.engine = engine;
        }

        public synchronized void messageReceived(Object message) throws Exception {
            IoBuffer ioBuffer = (IoBuffer) message;
            buffer.putBuffer(ioBuffer);
            if (buffer.available() < 4) {
                return;
            }
            int rpos = buffer.rpos();
            int len = buffer.getInt();
            buffer.rpos(rpos);
            if (buffer.available() < len + 4) {
                return;
            }
            Buffer rep = new Buffer();
            rep.putInt(0);
            rep.rpos(rep.wpos());
            try {
                process(new Buffer(buffer.getBytes()), rep);
            } catch (Exception e) {
                rep.clear();
                rep.putInt(1);
                rep.putByte(SSH2_AGENT_FAILURE);
            }
            reply(rep);
        }

        protected void process(Buffer req, Buffer rep) throws Exception {
            int cmd = req.getByte();
            switch (cmd) {
                case SSH2_AGENTC_REQUEST_IDENTITIES:
                {
                    List<SshAgent.Pair<PublicKey,String>> keys = engine.getIdentities();
                    rep.putByte(SSH2_AGENT_IDENTITIES_ANSWER);
                    rep.putInt(keys.size());
                    for (SshAgent.Pair<PublicKey,String> key : keys) {
                        rep.putPublicKey(key.getFirst());
                        rep.putString(key.getSecond());
                    }
                    break;
                }
                case SSH2_AGENTC_SIGN_REQUEST:
                {
                    PublicKey key = req.getPublicKey();
                    byte[] data = req.getBytes();
                    int flags = req.getInt();
                    Buffer sig = new Buffer();
                    sig.putString(key instanceof RSAPublicKey ? KeyPairProvider.SSH_RSA : KeyPairProvider.SSH_DSS);
                    sig.putBytes(engine.sign(key, data));
                    rep.putByte(SSH2_AGENT_SIGN_RESPONSE);
                    rep.putBytes(sig.array(), sig.rpos(), sig.available());
                    break;
                }
                case SSH2_AGENTC_ADD_IDENTITY:
                {
                    engine.addIdentity(req.getKeyPair(), req.getString());
                    rep.putByte(SSH_AGENT_SUCCESS);
                    break;
                }
                case SSH2_AGENTC_REMOVE_IDENTITY:
                {
                    PublicKey key = req.getPublicKey();
                    engine.removeIdentity(key);
                    rep.putByte(SSH_AGENT_SUCCESS);
                    break;
                }
                case SSH2_AGENTC_REMOVE_ALL_IDENTITIES:
                {
                    engine.removeAllIdentities();
                    rep.putByte(SSH_AGENT_SUCCESS);
                    break;
                }
                default:
                {
                    rep.putByte(SSH2_AGENT_FAILURE);
                    break;
                }
            }
        }

        protected void reply(Buffer buf) throws Exception {
            int len  = buf.available();
            int rpos = buf.rpos();
            int wpos = buf.wpos();
            buf.rpos(rpos - 4);
            buf.wpos(rpos - 4);
            buf.putInt(len);
            buf.wpos(wpos);
            IoBuffer b = IoBuffer.allocate(buf.available());
            b.put(buf.array(), buf.rpos(), buf.available());
            b.flip();
            session.write(b);
        }

    }

}
