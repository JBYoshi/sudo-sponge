/*
 * MIT License
 *
 * Copyright (c) 2018 Jonathan Browne
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package jbyoshi.sponge.sudo;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BindingProxy<T> {

    private final ClassLoader classLoader;
    private final Class<?>[] types;
    private final Set<MethodEntry> unassigned;
    private final Map<MethodEntry, MethodHandler> assigned;

    @SafeVarargs
    public BindingProxy(ClassLoader classLoader, Class<? super T>... types) {
        this.classLoader = classLoader;
        this.types = types.clone();
        this.assigned = new HashMap<>();
        this.unassigned = new HashSet<>();
        for (Class<?> type : types) {
            for (Method m : type.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    unassigned.add(new MethodEntry(m));
                }
            }
        }
    }

    public <U> BindingProxy<T> bindAll(Class<U> type, U object) {
        for (Method m : type.getMethods()) {
            MethodEntry entry = new MethodEntry(m);
            if (unassigned.remove(entry)) assigned.put(entry, (p, a) -> entry.invoke(object, a));
        }
        return this;
    }

    public Binder bind(String name, Class<?>... types) {
        MethodEntry entry = new MethodEntry(name, types);
        if (!unassigned.contains(entry)) throw new IllegalStateException("Method " + entry + " is already bound");
        return new Binder(entry);
    }

    public final class Binder {
        private final MethodEntry entry;

        private Binder(MethodEntry entry) {
            this.entry = entry;
        }

        public BindingProxy<T> to(MethodHandler handler) {
            if (!unassigned.remove(entry)) throw new IllegalStateException("Method " + entry + " is already bound");
            assigned.put(entry, handler);
            return BindingProxy.this;
        }
    }

    @SuppressWarnings("unchecked")
    public T build() {
        if (!this.unassigned.isEmpty()) {
            throw new IllegalStateException("Missing implementations for: " + this.unassigned);
        }
        return (T) Proxy.newProxyInstance(classLoader, this.types, this::invoke);
    }

    private Object invoke(Object p, Method m, Object[] a) throws Throwable {
        return this.assigned.get(new MethodEntry(m)).invoke(p, a);
    }

    interface MethodHandler {
        Object invoke(Object proxy, Object...args) throws Throwable;
    }

    private static final class MethodEntry {
        private final String name;
        private final Class<?>[] types;

        MethodEntry(Method m) {
            this.name = m.getName();
            this.types = m.getParameterTypes().clone();
        }

        MethodEntry(String name, Class<?>[] types) {
            this.name = name;
            this.types = types.clone();
        }

        Object invoke(Object target, Object[] args) throws ReflectiveOperationException {
            return target.getClass().getMethod(name, types).invoke(target, args);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MethodEntry entry = (MethodEntry) o;
            return Objects.equals(name, entry.name) &&
                    Arrays.equals(types, entry.types);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(name);
            result = 31 * result + Arrays.hashCode(types);
            return result;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(name).append("(");
            for (int i = 0; i < types.length; i++) {
                if (i > 0) builder.append(", ");
                builder.append(types[i].getName());
            }
            return builder.append(")").toString();
        }
    }
}
