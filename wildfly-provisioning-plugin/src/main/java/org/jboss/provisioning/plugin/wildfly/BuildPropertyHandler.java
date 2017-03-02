/*
 * Copyright 2016-2017 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.provisioning.plugin.wildfly;


import java.io.File;

/**
 * Replace properties of the form:
 * <code>${<i>&lt;[env.]name&gt;[</i>,<i>&lt;[env.]name2&gt;[</i>,<i>&lt;[env.]name3&gt;...]][</i>]</i>}</code>
 *
 * @author Stuart Douglas
 * @author Jaikiran Pai (copied from JBoss DMR project)
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author John Bailey
 */
public class BuildPropertyHandler {

    public interface ContentHandler {
        void codePoint(int c);

        void character(char c);

        void string(String str);
    }

    private static ContentHandler NOOP = new ContentHandler() {
        @Override
        public void codePoint(int c) {
        }

        @Override
        public void character(char c) {
        }

        @Override
        public void string(String str) {
        }
    };

    private static final int INITIAL = 0;
    private static final int GOT_DOLLAR = 1;
    private static final int GOT_OPEN_BRACE = 2;
    private static final int RESOLVED = 3;
    private static final int DEFAULT = 4;

    private final PropertyResolver properties;

    public BuildPropertyHandler(PropertyResolver properties) {
        this.properties = properties;
    }

    public PropertyResolver getPropertyResolver() {
        return properties;
    }

    public String replaceProperties(final String value) {
        return replaceProperties(value, properties);
    }

    public String replaceProperties(final String value, PropertyResolver properties) {
        final StringBuilder sb = new StringBuilder();
        final ContentHandler handler = new ContentHandler() {
            @Override
            public void codePoint(int c) {
                sb.appendCodePoint(c);
            }

            @Override
            public void character(char c) {
                sb.append(c);
            }

            @Override
            public void string(String str) {
                sb.append(str);
            }
        };
        handleProperties(value, handler, properties);
        return sb.toString();
    }

    public void handlePoperties(final String value, final PropertyResolver properties) {
        handleProperties(value, NOOP, properties);
    }

    private void handleProperties(final String value, ContentHandler handler, final PropertyResolver properties) {
        final int len = value.length();
        int state = INITIAL;
        int start = -1;
        int nameStart = -1;
        int expressionStart = -1;
        String resolvedValue = null;
        for (int i = 0; i < len; i = value.offsetByCodePoints(i, 1)) {
            final int ch = value.codePointAt(i);
            switch (state) {
                case INITIAL: {
                    switch (ch) {
                        case '$': {
                            state = GOT_DOLLAR;
                            continue;
                        }
                        default: {
                            handler.codePoint(ch);
                            continue;
                        }
                    }
                    // not reachable
                }
                case GOT_DOLLAR: {
                    switch (ch) {
                        case '$': {
                            handler.codePoint(ch);
                            state = INITIAL;
                            continue;
                        }
                        case '{': {
                            start = i + 1;
                            expressionStart = nameStart = start;
                            state = GOT_OPEN_BRACE;
                            continue;
                        }
                        default: {
                            // invalid; emit and resume
                            handler.character('$');
                            handler.codePoint(ch);
                            state = INITIAL;
                            continue;
                        }
                    }
                    // not reachable
                }
                case GOT_OPEN_BRACE: {
                    switch (ch) {
                        case '}':
                        case ',': {
                            final String name = value.substring(nameStart, i).trim();
                            if ("/".equals(name)) {
                                handler.character(File.separatorChar);
                                state = ch == '}' ? INITIAL : RESOLVED;
                                continue;
                            }
                            final String val = properties.resolveProperty(name);
                            if (val != null) {
                                handler.string(val);
                                resolvedValue = val;
                                state = ch == '}' ? INITIAL : RESOLVED;
                                continue;
                            } else if (ch == ',') {
                                nameStart = i + 1;
                                continue;
                            } else {
                                // check and ignore options if there are any
                                int q = name.indexOf('?');
                                if(q > 0) {
                                    final String name2 = name.substring(0, q);
                                    final String val2 = properties.resolveProperty(name2);
                                    if (val2 != null) {
                                        handler.string(val2);
                                        resolvedValue = val2;
                                        state = ch == '}' ? INITIAL : RESOLVED;
                                        continue;
                                    } else if (ch == ',') {
                                        nameStart = i + 1;
                                        continue;
                                    }
                                }
                                throw new IllegalStateException("Failed to resolve expression: " + value.substring(start - 2, i + 1));
                            }
                        }
                        default: {
                            continue;
                        }
                    }
                    // not reachable
                }
                case RESOLVED: {
                    if (ch == '}') {
                        state = INITIAL;
                    }
                    continue;
                }
                case DEFAULT: {
                    if (ch == '}') {
                        state = INITIAL;
                        // JBMETA-371 check in case the whole expression was meant to be resolved
                        final String val = properties.resolveProperty(value.substring(expressionStart, i));
                        if (val != null) {
                            handler.string(val);
                        } else {
                            handler.string(value.substring(start, i));
                        }
                    }
                    continue;
                }
                default:
                    throw new IllegalStateException("Unexpected char seen: " + ch);
            }
        }
        switch (state) {
            case GOT_DOLLAR: {
                handler.character('$');
                break;
            }
            case DEFAULT: {
                handler.string(value.substring(start - 2));
                break;
            }
            case GOT_OPEN_BRACE: {
                // We had a reference that was not resolved, throw ISE
                if (resolvedValue == null)
                    throw new IllegalStateException("Incomplete expression: " + handler.toString());
                break;
            }
        }
    }
}