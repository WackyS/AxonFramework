/*
 * Copyright (c) 2010-2020. Axon Framework
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

package org.axonframework.commandhandling.gateway;

import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.CommandResultMessage;
import org.axonframework.commandhandling.GenericCommandMessage;
import org.axonframework.commandhandling.callbacks.ReactiveCallback;
import org.axonframework.common.AxonConfigurationException;
import org.axonframework.common.Registration;
import org.axonframework.messaging.reactive.ReactiveMessageDispatchInterceptor;
import org.axonframework.messaging.reactive.ReactiveResultHandlerInterceptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Arrays.asList;
import static org.axonframework.common.BuilderUtils.assertNonNull;

/**
 * Implementation of the {@link ReactiveCommandGateway} that uses Project Reactor to achieve reactiveness.
 *
 * @author Milan Savic
 * @since 4.4
 */
public class ReactorCommandGateway implements ReactiveCommandGateway {

    private final CommandBus commandBus;
    private final RetryScheduler retryScheduler;
    private final List<ReactiveMessageDispatchInterceptor<CommandMessage<?>>> dispatchInterceptors;
    private final List<ReactiveResultHandlerInterceptor<CommandMessage<?>, CommandResultMessage<?>>> resultInterceptors;

    /**
     * Creates an instance of {@link ReactorCommandGateway} based on the fields contained in the {@link
     * Builder}.
     * <p>
     * Will assert that the {@link CommandBus} is not {@code null} and throws an {@link AxonConfigurationException} if
     * it is.
     * </p>
     *
     * @param builder the {@link Builder} used to instantiated a {@link ReactorCommandGateway} instance
     */
    protected ReactorCommandGateway(Builder builder) {
        builder.validate();
        this.commandBus = builder.commandBus;
        this.retryScheduler = builder.retryScheduler;
        this.dispatchInterceptors = builder.dispatchInterceptors;
        this.resultInterceptors = builder.resultInterceptors;
    }

    /**
     * Instantiate a Builder to be able to create a {@link ReactorCommandGateway}.
     * <p>
     * The {@code dispatchInterceptors} are defaulted to an empty list.
     * The {@link CommandBus} is a <b>hard requirements</b> and as such should be provided.
     * </p>
     *
     * @return a Builder to be able to create a {@link ReactorCommandGateway}
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public <R> Mono<R> send(Object command) {
        //noinspection unchecked
        return Mono.<CommandMessage<?>>just(GenericCommandMessage.asCommandMessage(command))
                .transform(this::processCommandInterceptors)
                .flatMap(this::dispatchCommand)
                .flatMap(this::processResultsInterceptors)
                .map(resultMessage -> (R) resultMessage.getPayload());
    }

    private Mono<CommandMessage<?>> processCommandInterceptors(Mono<CommandMessage<?>> commandMessage) {
        return Flux.fromIterable(dispatchInterceptors)
                   .reduce(commandMessage, (command, interceptor) -> interceptor.intercept(command))
                   .flatMap(Mono::from);
    }

    private <C, R> Mono<Tuple2<CommandMessage<C>, Flux<CommandResultMessage<? extends R>>>> dispatchCommand(
            CommandMessage<C> commandMessage) {
        ReactiveCallback<C, R> reactiveCallback = new ReactiveCallback<>();
        CommandCallback<C, R> callback = reactiveCallback;
        if (retryScheduler != null) {
            callback = new RetryingCallback<>(callback, retryScheduler, commandBus);
        }
        commandBus.dispatch(commandMessage, callback);
        return Mono.just(commandMessage).zipWith(Mono.just(Flux.from(reactiveCallback)));
    }

    private <C> Mono<? extends CommandResultMessage<?>> processResultsInterceptors(
            Tuple2<CommandMessage<C>, Flux<CommandResultMessage<?>>> commandWithResults) {
        CommandMessage<?> commandMessage = commandWithResults.getT1();
        Flux<CommandResultMessage<?>> commandResultMessages = commandWithResults.getT2();
        return Flux.fromIterable(resultInterceptors)
                   .reduce(commandResultMessages,
                           (result, interceptor) -> interceptor.intercept(commandMessage, result))
                   .flatMap(Flux::next); // command handlers provide only one result!
    }

    @Override
    public Registration registerDispatchInterceptor(ReactiveMessageDispatchInterceptor<CommandMessage<?>> interceptor) {
        dispatchInterceptors.add(interceptor);
        return () -> dispatchInterceptors.remove(interceptor);
    }

    @Override
    public Registration registerResultHandlerInterceptor(
            ReactiveResultHandlerInterceptor<CommandMessage<?>, CommandResultMessage<?>> interceptor) {
        resultInterceptors.add(interceptor);
        return () -> resultInterceptors.remove(interceptor);
    }

    /**
     * Builder class to instantiate {@link ReactorCommandGateway}.
     * <p>
     * The {@code dispatchInterceptors} are defaulted to an empty list.
     * The {@link CommandBus} is a <b>hard requirement</b> and as such should be provided.
     * </p>
     */
    public static class Builder {

        private CommandBus commandBus;
        private RetryScheduler retryScheduler;
        private List<ReactiveMessageDispatchInterceptor<CommandMessage<?>>> dispatchInterceptors = new CopyOnWriteArrayList<>();
        private List<ReactiveResultHandlerInterceptor<CommandMessage<?>, CommandResultMessage<?>>> resultInterceptors = new CopyOnWriteArrayList<>();

        /**
         * Sets the {@link CommandBus} used to dispatch commands.
         *
         * @param commandBus a {@link CommandBus} used to dispatch commands
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder commandBus(CommandBus commandBus) {
            assertNonNull(commandBus, "CommandBus may not be null");
            this.commandBus = commandBus;
            return this;
        }

        /**
         * Sets the {@link RetryScheduler} capable of performing retries of failed commands. May be {@code null} when
         * to prevent retries.
         *
         * @param retryScheduler a {@link RetryScheduler} capable of performing retries of failed commands
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder retryScheduler(RetryScheduler retryScheduler) {
            this.retryScheduler = retryScheduler;
            return this;
        }

        /**
         * Sets the {@link List} of {@link ReactiveMessageDispatchInterceptor}s for {@link CommandMessage}s. Are invoked
         * when a command is being dispatched.
         *
         * @param dispatchInterceptors which are invoked when a command is being dispatched
         * @return the current Builder instance, for fluent interfacing
         */
        @SafeVarargs
        public final Builder dispatchInterceptors(
                ReactiveMessageDispatchInterceptor<CommandMessage<?>>... dispatchInterceptors) {
            return dispatchInterceptors(asList(dispatchInterceptors));
        }

        /**
         * Sets the {@link List} of {@link ReactiveMessageDispatchInterceptor}s for {@link CommandMessage}s. Are invoked
         * when a command is being dispatched.
         *
         * @param dispatchInterceptors which are invoked when a command is being dispatched
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder dispatchInterceptors(
                List<ReactiveMessageDispatchInterceptor<CommandMessage<?>>> dispatchInterceptors) {
            this.dispatchInterceptors = dispatchInterceptors != null && dispatchInterceptors.isEmpty()
                    ? new CopyOnWriteArrayList<>(dispatchInterceptors)
                    : new CopyOnWriteArrayList<>();
            return this;
        }

        /**
         * Sets the {@link List} of {@link ReactiveResultHandlerInterceptor}s for {@link CommandResultMessage}s.
         * Are invoked when a result has been received.
         *
         * @param resultHandlerInterceptors which are invoked when a result has been received
         * @return the current Builder instance, for fluent interfacing
         */
        @SafeVarargs
        public final Builder resultHandlerInterceptors(
                ReactiveResultHandlerInterceptor<CommandMessage<?>, CommandResultMessage<?>>... resultHandlerInterceptors) {
            return resultHandlerInterceptors(asList(resultHandlerInterceptors));
        }

        /**
         * Sets the {@link List} of {@link ReactiveResultHandlerInterceptor}s for {@link CommandResultMessage}s.
         * Are invoked when a result has been received.
         *
         * @param resultHandlerInterceptors which are invoked when a result has been received
         * @return the current Builder instance, for fluent interfacing
         */
        public Builder resultHandlerInterceptors(
                List<ReactiveResultHandlerInterceptor<CommandMessage<?>, CommandResultMessage<?>>> resultHandlerInterceptors) {
            this.resultInterceptors = resultHandlerInterceptors != null && resultHandlerInterceptors.isEmpty()
                    ? new CopyOnWriteArrayList<>(resultHandlerInterceptors)
                    : new CopyOnWriteArrayList<>();
            return this;
        }

        /**
         * Validate whether the fields contained in this Builder as set accordingly.
         *
         * @throws AxonConfigurationException if one field is asserted to be incorrect according to the Builder's
         *                                    specifications
         */
        protected void validate() {
            assertNonNull(commandBus, "The CommandBus is a hard requirement and should be provided");
        }

        /**
         * Initializes a {@link ReactorCommandGateway} as specified through this Builder.
         *
         * @return a {@link ReactorCommandGateway} as specified through this Builder
         */
        public ReactorCommandGateway build() {
            return new ReactorCommandGateway(this);
        }
    }
}
