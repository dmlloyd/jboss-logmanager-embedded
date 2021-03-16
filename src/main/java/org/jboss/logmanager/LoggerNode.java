/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.logmanager;

import com.oracle.svm.core.annotate.AlwaysInline;
import org.wildfly.common.lock.SpinLock;

import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.ErrorManager;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;

import static java.lang.Math.max;

/**
 * A node in the tree of logger names.  Maintains weak references to children and a strong reference to its parent.
 */
final class LoggerNode {

    /**
     * The log context.
     */
    private final LogContext context;
    /**
     * The parent node, or {@code null} if this is the root logger node.
     */
    private final LoggerNode parent;
    /**
     * The fully-qualified name of this logger.
     */
    private final String fullName;

    /**
     * The map of names to child nodes.  The child node references are weak.
     */
    private final ConcurrentMap<String, LoggerNode> children;

    /**
     * The handlers for this logger.  The array instance should not be modified (treat as immutable).
     */
    private final AtomicReference<Handler[]> handlersRef = new AtomicReference<>();

    /**
     * Flag to specify whether parent handlers are used.
     */
    private volatile boolean useParentHandlers = true;

    /**
     * The filter for this logger instance.
     */
    private volatile Filter filter;

    /**
     * Flag to specify whether parent filters are used.
     */
    private volatile boolean useParentFilter = false;

    /**
     * The actual level.  May only be modified when the context's level change lock is held; in addition, changing
     * this field must be followed immediately by recursively updating the effective loglevel of the child tree.
     */
    private volatile java.util.logging.Level level;
    /**
     * The effective level.  May only be modified when the context's level change lock is held; in addition, changing
     * this field must be followed immediately by recursively updating the effective loglevel of the child tree.
     */
    private volatile int effectiveLevel;

    private final int effectiveMinLevel;

    private volatile boolean hasLogger;

    private final SpinLock attachmentLock = new SpinLock();

    private Logger.AttachmentKey<?> att1Key;
    private Object att1;

    private Logger.AttachmentKey<?> att2Key;
    private Object att2;

    private Logger.AttachmentKey<?> att3Key;
    private Object att3;

    /**
     * Construct a new root instance.
     *
     * @param context the logmanager
     */
    LoggerNode(final LogContext context) {
        final EmbeddedConfigurator configurator = LogContext.CONFIGURATOR;
        parent = null;
        fullName = "";
        final Level minLevel = configurator.getMinimumLevelOf(fullName);
        effectiveMinLevel = minLevel == null ? Logger.INFO_INT : minLevel.intValue();
        final Level confLevel = configurator.getLevelOf(fullName);
        level = confLevel == null ? Level.INFO : confLevel;
        effectiveLevel = max(effectiveMinLevel, level.intValue());
        handlersRef.set(configurator.getHandlersOf(fullName));
        this.context = context;
        children = new CopyOnWriteMap<String, LoggerNode>();
    }

    /**
     * Construct a child instance.
     *
     * @param context the logmanager
     * @param parent the parent node
     * @param nodeName the name of this subnode
     */
    private LoggerNode(LogContext context, LoggerNode parent, String nodeName) {
        final EmbeddedConfigurator configurator = LogContext.CONFIGURATOR;
        nodeName = nodeName.trim();
        if (nodeName.length() == 0 && parent == null) {
            throw new IllegalArgumentException("nodeName is empty, or just whitespace and has no parent");
        }
        this.parent = parent;
        if (parent.parent == null) {
            if (nodeName.isEmpty()) {
                fullName = ".";
            } else {
                fullName = nodeName;
            }
        } else {
            fullName = parent.fullName + "." + nodeName;
        }
        final Level minLevel = configurator.getMinimumLevelOf(fullName);
        effectiveMinLevel = minLevel == null ? parent.effectiveMinLevel : minLevel.intValue();
        level = configurator.getLevelOf(fullName);
        effectiveLevel = max(effectiveMinLevel, level == null ? parent.effectiveLevel : level.intValue());
        handlersRef.set(configurator.getHandlersOf(fullName));
        this.context = context;
        children = new CopyOnWriteMap<String, LoggerNode>();
    }

    /**
     * Get or create a relative logger node.  The name is relatively qualified to this node.
     *
     * @param name the name
     * @return the corresponding logger node
     */
    LoggerNode getOrCreate(final String name) {
        if (name == null || name.length() == 0) {
            return this;
        } else {
            int i = name.indexOf('.');
            final String nextName = i == -1 ? name : name.substring(0, i);
            LoggerNode nextNode = children.get(nextName);
            if (nextNode == null) {
                nextNode = new LoggerNode(context, this, nextName);
                LoggerNode appearingNode = children.putIfAbsent(nextName, nextNode);
                if (appearingNode != null) {
                    nextNode = appearingNode;
                }
            }
            if (i == -1) {
                return nextNode;
            } else {
                return nextNode.getOrCreate(name.substring(i + 1));
            }
        }
    }

    /**
     * Get a relative logger, if it exists.
     *
     * @param name the name
     * @return the corresponding logger
     */
    LoggerNode getIfExists(final String name) {
        if (name == null || name.length() == 0) {
            return this;
        } else {
            int i = name.indexOf('.');
            final String nextName = i == -1 ? name : name.substring(0, i);
            LoggerNode nextNode = children.get(nextName);
            if (nextNode == null) {
                return null;
            }
            if (i == -1) {
                return nextNode;
            } else {
                return nextNode.getIfExists(name.substring(i + 1));
            }
        }
    }

    Logger createLogger() {
        hasLogger = true;
        return new Logger(this, fullName);
    }

    /**
     * Get the children of this logger.
     *
     * @return the children
     */
    Collection<LoggerNode> getChildren() {
        return children.values();
    }

    /**
     * Get the log context.
     *
     * @return the log context
     */
    LogContext getContext() {
        return context;
    }

    /**
     * Update the effective level if it is inherited from a parent.  Must only be called while the logmanager's level
     * change lock is held.
     *
     * @param newLevel the new effective level
     */
    void setEffectiveLevel(int newLevel) {
        if (level == null) {
            effectiveLevel = newLevel;
            for (LoggerNode node : children.values()) {
                if (node != null) {
                    node.setEffectiveLevel(newLevel);
                }
            }
        }
    }

    void setFilter(final Filter filter) {
        this.filter = filter;
    }

    Filter getFilter() {
        return filter;
    }

    boolean getUseParentFilters() {
        return useParentFilter;
    }

    void setUseParentFilters(final boolean useParentFilter) {
        this.useParentFilter = useParentFilter;
    }

    @AlwaysInline("Fast level checks")
    int getEffectiveLevel() {
        return effectiveLevel;
    }

    @AlwaysInline("Fast level checks")
    boolean isLoggableLevel(int level) {
        return level != Logger.OFF_INT && level >= effectiveMinLevel && level >= effectiveLevel;
    }

    Handler[] getHandlers() {
        Handler[] handlers = handlersRef.get();
        if (handlers == null) {
            synchronized (this) {
                handlers = LogContext.CONFIGURATOR.getHandlersOf(fullName);
                handlersRef.set(handlers);
            }
        }
        return handlers;
    }

    Handler[] clearHandlers() {
        return setHandlers(EmbeddedConfigurator.NO_HANDLERS);
    }

    void removeHandler(final Handler handler) {
        retry: for (;;) {
            Handler[] handlers = getHandlers();
            int length = handlers.length;
            for (int i = 0; i < length; i++) {
                if (handlers[i] == handler) {
                    Handler[] newHandlers = Arrays.copyOf(handlers, length - 1);
                    System.arraycopy(handlers, i + 1, newHandlers, i, length - i - 1);
                    if (! compareAndSetHandlers(handlers, newHandlers)) {
                        continue retry;
                    }
                    return;
                }
            }
            // not found
            return;
        }
    }

    void addHandler(final Handler handler) {
        Handler[] oldVal, newVal;
        int len;
        do {
            oldVal = getHandlers();
            len = oldVal.length;
            newVal = Arrays.copyOf(oldVal, len + 1);
            newVal[len] = handler;
        } while (! handlersRef.compareAndSet(oldVal, newVal));
    }

    Handler[] setHandlers(final Handler[] handlers) {
        return handlersRef.getAndSet(handlers);
    }

    boolean compareAndSetHandlers(final Handler[] oldHandlers, final Handler[] newHandlers) {
        return handlersRef.compareAndSet(oldHandlers, newHandlers);
    }

    boolean getUseParentHandlers() {
        return useParentHandlers;
    }

    void setUseParentHandlers(final boolean useParentHandlers) {
        this.useParentHandlers = useParentHandlers;
    }

    void publish(final ExtLogRecord record) {
        for (Handler handler : getHandlers()) try {
            handler.publish(record);
        } catch (Exception e) {
            ExtHandler.reportError(handler, "Handler publication threw an exception", e, ErrorManager.WRITE_FAILURE);
        } catch (Throwable ignored) {
        }
        if (useParentHandlers) {
            final LoggerNode parent = this.parent;
            if (parent != null) parent.publish(record);
        }
    }

    void setLevel(final Level newLevel) {
        final LogContext context = this.context;
        final Object lock = context.treeLock;
        synchronized (lock) {
            final int oldEffectiveLevel = effectiveLevel;
            final int newEffectiveLevel;
            if (newLevel != null) {
                level = newLevel;
                newEffectiveLevel = newLevel.intValue();
            } else {
                final LoggerNode parent = this.parent;
                if (parent == null) {
                    level = Level.INFO;
                    newEffectiveLevel = Logger.INFO_INT;
                } else {
                    level = null;
                    newEffectiveLevel = parent.effectiveLevel;
                }
            }
            effectiveLevel = newEffectiveLevel;
            if (oldEffectiveLevel != newEffectiveLevel) {
                // our level changed, recurse down to children
                for (LoggerNode node : children.values()) {
                    if (node != null) {
                        node.setEffectiveLevel(newEffectiveLevel);
                    }
                }
            }
        }
    }

    Level getLevel() {
        return level;
    }

    @SuppressWarnings({ "unchecked" })
    <V> V getAttachment(final Logger.AttachmentKey<V> key) {
        if (key == null) {
            throw new NullPointerException("key is null");
        }
        final SpinLock lock = this.attachmentLock;
        lock.lock();
        try {
            return key == att1Key ? (V) att1 : key == att2Key ? (V) att2 : key == att3Key ? (V) att3 : null;
        } finally {
            lock.unlock();
        }
    }

    @SuppressWarnings({ "unchecked" })
    <V> V attach(final Logger.AttachmentKey<V> key, final V value) {
        if (key == null) {
            throw new NullPointerException("key is null");
        }
        if (value == null) {
            throw new NullPointerException("value is null");
        }
        final SpinLock lock = this.attachmentLock;
        lock.lock();
        try {
            Logger.AttachmentKey<?> attachmentKey = this.att1Key;
            if (attachmentKey == null) {
                this.att1Key = key;
                att1 = value;
                return null;
            } else if (key == attachmentKey) {
                try {
                    return (V) att1;
                } finally {
                    att1 = value;
                }
            }
            attachmentKey = this.att2Key;
            if (attachmentKey == null) {
                this.att2Key = key;
                att2 = value;
                return null;
            } else if (key == attachmentKey) {
                try {
                    return (V) att2;
                } finally {
                    att2 = value;
                }
            }
            attachmentKey = this.att3Key;
            if (attachmentKey == null) {
                this.att3Key = key;
                att3 = value;
                return null;
            } else if (key == attachmentKey) {
                try {
                    return (V) att3;
                } finally {
                    att3 = value;
                }
            }
        } finally {
            lock.unlock();
        }
        throw new IllegalStateException("Maximum number of attachments exceeded");
    }

    @SuppressWarnings({ "unchecked" })
    <V> V attachIfAbsent(final Logger.AttachmentKey<V> key, final V value) {
        if (key == null) {
            throw new NullPointerException("key is null");
        }
        if (value == null) {
            throw new NullPointerException("value is null");
        }
        final SpinLock lock = this.attachmentLock;
        lock.lock();
        try {
            Logger.AttachmentKey<?> attachmentKey = this.att1Key;
            if (attachmentKey == null) {
                this.att1Key = key;
                att1 = value;
                return null;
            } else if (key == attachmentKey) {
                return (V) att1;
            }
            attachmentKey = this.att2Key;
            if (attachmentKey == null) {
                this.att2Key = key;
                att2 = value;
                return null;
            } else if (key == attachmentKey) {
                return (V) att2;
            }
            attachmentKey = this.att3Key;
            if (attachmentKey == null) {
                this.att3Key = key;
                att3 = value;
                return null;
            } else if (key == attachmentKey) {
                return (V) att3;
            }
        } finally {
            lock.unlock();
        }
        throw new IllegalStateException("Maximum number of attachments exceeded");
    }

    @SuppressWarnings({ "unchecked" })
    public <V> V detach(final Logger.AttachmentKey<V> key) {
        if (key == null) {
            throw new NullPointerException("key is null");
        }
        final SpinLock lock = this.attachmentLock;
        lock.lock();
        try {
            if (key == att1Key) {
                try {
                    return (V) att1;
                } finally {
                    this.att1Key = null;
                    this.att1 = null;
                }
            } else if (key == att2Key) {
                try {
                    return (V) att2;
                } finally {
                    this.att2Key = null;
                    this.att2 = null;
                }
            } else if (key == att3Key) {
                try {
                    return (V) att3;
                } finally {
                    this.att3Key = null;
                    this.att3 = null;
                }
            } else {
                return null;
            }
        } finally {
            lock.unlock();
        }
    }

    LoggerNode getParent() {
        return parent;
    }

    /**
     * Checks the filter to see if the record is loggable. If the {@link #getUseParentFilters()} is set to {@code true}
     * the parent loggers are checked.
     *
     * @param record the log record to check against the filter
     *
     * @return {@code true} if the record is loggable, otherwise {@code false}
     */
    boolean isLoggable(final ExtLogRecord record) {
        if (!useParentFilter) {
            final Filter filter = this.filter;
            return filter == null || filter.isLoggable(record);
        }
        final LogContext context = this.context;
        final Object lock = context.treeLock;
        synchronized (lock) {
            return isLoggable(this, record);
        }
    }

    private static boolean isLoggable(final LoggerNode loggerNode, final ExtLogRecord record) {
        if (loggerNode == null) {
            return true;
        }
        final Filter filter = loggerNode.filter;
        return !(filter != null && !filter.isLoggable(record)) && (!loggerNode.useParentFilter || isLoggable(loggerNode.getParent(), record));
    }

    public boolean hasLogger() {
        return hasLogger;
    }

    public String getFullName() {
        return fullName;
    }
}
