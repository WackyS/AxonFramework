/*
 * Copyright (c) 2010-2022. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.modelling.command;

import org.axonframework.messaging.ScopeAware;

import java.util.concurrent.Callable;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The repository provides an abstraction of the storage of aggregates.
 *
 * @param <T> The type of aggregate this repository stores.
 */
public interface Repository<T> extends ScopeAware {

    /**
     * Load the aggregate with the given unique identifier. No version checks are done when loading an aggregate,
     * meaning that concurrent access will not be checked for.
     *
     * @param aggregateIdentifier The identifier of the aggregate to load
     * @return The aggregate root with the given identifier.
     * @throws AggregateNotFoundException if aggregate with given id cannot be found
     */
    Aggregate<T> load(@Nonnull String aggregateIdentifier);

    /**
     * Load the aggregate with the given unique identifier.
     *
     * @param aggregateIdentifier The identifier of the aggregate to load
     * @param expectedVersion     The expected version of the loaded aggregate
     * @return The aggregate root with the given identifier.
     *
     * @throws AggregateNotFoundException if aggregate with given id cannot be found
     */
    Aggregate<T> load(@Nonnull String aggregateIdentifier, @Nullable Long expectedVersion);

    /**
     * Creates a new managed instance for the aggregate, using the given {@code factoryMethod}
     * to instantiate the aggregate's root.
     *
     * @param factoryMethod The method to create the aggregate's root instance
     * @return an Aggregate instance describing the aggregate's state
     *
     * @throws Exception when the factoryMethod throws an exception
     */
    Aggregate<T> newInstance(@Nonnull Callable<T> factoryMethod) throws Exception;

    /**
     * Loads an aggregate from the repository. If the aggregate is not found it creates the aggregate using the
     * specified
     * {@code factoryMethod}.
     *
     * @param aggregateIdentifier The identifier of the aggregate to load
     * @param factoryMethod       The method to create the aggregate's root instance
     * @return The aggregate root with the given identifier.
     */
    default Aggregate<T> loadOrCreate(@Nonnull String aggregateIdentifier, @Nonnull Callable<T> factoryMethod)
            throws Exception {
        throw new UnsupportedOperationException("loadOrCreate not implemented on this repository");
    }
}
