/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.redhat.hacep.cache.session;

import it.redhat.hacep.configuration.DroolsConfiguration;
import it.redhat.hacep.model.Fact;
import it.redhat.hacep.model.Key;
import it.redhat.hacep.model.SessionKey;
import org.infinispan.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SessionSaver {

    private static final Logger logger = LoggerFactory.getLogger(SessionSaver.class);
    private static final Logger audit = LoggerFactory.getLogger("audit.redhat.hacep");

    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Cache<Key, Object> sessionCache;
    private DroolsConfiguration droolsConfiguration;

    public SessionSaver(Cache<Key, Object> sessionCache, DroolsConfiguration droolsConfiguration) {
        this.sessionCache = sessionCache;
        this.droolsConfiguration = droolsConfiguration;
    }

    public SessionSaver insert(Key key, Fact fact) {
        SessionKey sessionKey = new SessionKey(key.getGroup());
        audit.info(key + " | " + fact + " | COD_21 | starting to insert fact");
        synchronized (getLock(sessionKey.toString())) {
            HASession haSession;
            Object value = sessionCache.get(sessionKey);
            if (value == null) {
                haSession = new HASession(droolsConfiguration);
                sessionCache.put(sessionKey, haSession);
            } else if (HASerializedSession.class.isAssignableFrom(value.getClass())) {
                haSession = ((HASerializedSession) value).rebuild();
            } else {
                haSession = (HASession) value;
            }
            haSession.insert(fact);
            audit.info(key + " | " + fact + " | COD_23 | rules invoked");
            sessionCache.put(sessionKey, haSession);
            audit.info(key + " | " + fact + " | COD_24 | fact inserted");
        }
        return this;
    }

    //@todo must be evaluated. In production code something like [1] or use infinispan locking (verifying that everything happens locally)
    // [1] https://github.com/ModeShape/modeshape/blob/master/modeshape-jcr/src/main/java/org/modeshape/jcr/value/binary/NamedLocks.java
    private Object getLock(String name) {
        Object lock = locks.get(name);
        if (lock == null) {
            Object newLock = new Object();
            lock = locks.putIfAbsent(name, newLock);
            if (lock == null) {
                lock = newLock;
            }
        }
        return lock;
    }
}
