/*******************************************************************************
 * Copyright (c) 2020, 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/

package org.eclipse.hono.commandrouter.impl.amqp;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.hono.adapter.client.amqp.AbstractServiceClient;
import org.eclipse.hono.client.HonoConnection;
import org.eclipse.hono.client.MessageConsumer;
import org.eclipse.hono.client.SendMessageSampler;
import org.eclipse.hono.client.ServerErrorException;
import org.eclipse.hono.client.impl.CachingClientFactory;
import org.eclipse.hono.client.impl.CommandConsumer;
import org.eclipse.hono.commandrouter.CommandConsumerFactory;
import org.eclipse.hono.commandrouter.CommandRouterServiceConfigProperties;
import org.eclipse.hono.commandrouter.CommandTargetMapper;
import org.eclipse.hono.util.AddressHelper;
import org.eclipse.hono.util.CommandConstants;

import io.opentracing.SpanContext;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.proton.ProtonQoS;

/**
 * A factory for creating clients for the <em>AMQP 1.0 Messaging Network</em> to receive commands.
 * <p>
 * The factory uses tenant-scoped links, created (if not already existing for the tenant) when
 * {@link CommandConsumerFactory#createCommandConsumer(String, SpanContext)} is invoked.
 * <p>
 * Command messages are first received on the tenant-scoped consumer address. It is then determined which protocol
 * adapter instance can handle the command. The command is then forwarded to the AMQP messaging network on
 * an address containing that adapter instance id.
 */
public class ProtonBasedCommandConsumerFactoryImpl extends AbstractServiceClient implements
        CommandConsumerFactory {

    private static final int RECREATE_CONSUMERS_DELAY = 20;

    /**
     * Cache key used here is the tenant id.
     */
    private CachingClientFactory<MessageConsumer> mappingAndDelegatingCommandConsumerFactory;

    private final AtomicBoolean recreatingConsumers = new AtomicBoolean(false);
    private final AtomicBoolean tryAgainRecreatingConsumers = new AtomicBoolean(false);

    private MappingAndDelegatingCommandHandler mappingAndDelegatingCommandHandler;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    /**
     * List of tenant ids corresponding to the tenants for which consumers have been registered.
     */
    private final Set<String> consumerLinkTenants = new HashSet<>(); 

    /**
     * Creates a new factory for an existing connection.
     *
     * @param connection The connection to the AMQP network.
     * @param samplerFactory The sampler factory to use.
     * @param config The component's configuration properties.
     * @throws NullPointerException if any of the parameters is {@code null}.
     */
    public ProtonBasedCommandConsumerFactoryImpl(
            final HonoConnection connection,
            final SendMessageSampler.Factory samplerFactory,
            final CommandRouterServiceConfigProperties config) {
        super(connection, samplerFactory, config);
    }

    @Override
    public void initialize(final CommandTargetMapper commandTargetMapper) {
        Objects.requireNonNull(commandTargetMapper);

        mappingAndDelegatingCommandHandler = new MappingAndDelegatingCommandHandler(connection,
                commandTargetMapper, samplerFactory.create(CommandConstants.COMMAND_ENDPOINT));
        mappingAndDelegatingCommandConsumerFactory = new CachingClientFactory<>(connection.getVertx(), c -> true);

// TODO implement the equivalent of a tenant timeout mechanism as used in the protocol adapters in order to close unused tenant-scoped receiver links
//        connection.getVertx().eventBus().consumer(Constants.EVENT_BUS_ADDRESS_TENANT_TIMED_OUT,
//                this::handleTenantTimeout);
        connection.addReconnectListener(c -> recreateConsumers());
        // trigger creation of adapter specific consumer link (with retry if failed)
        recreateConsumers();
        initialized.set(true);
    }

    @Override
    protected void onDisconnect() {
        mappingAndDelegatingCommandConsumerFactory.clearState();
        consumerLinkTenants.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final Future<Void> createCommandConsumer(final String tenantId, final SpanContext context) {
        Objects.requireNonNull(tenantId);

        if (!initialized.get()) {
            log.error("not initialized");
            return Future.failedFuture(new ServerErrorException(HttpURLConnection.HTTP_INTERNAL_ERROR));
        }
        return connection.executeOnContext(result -> {
            // create the tenant-scoped consumer ("command/<tenantId>") that maps/delegates incoming commands to the right adapter-instance
            getOrCreateMappingAndDelegatingCommandConsumer(tenantId)
                    .map((Void) null)
                    .onComplete(result);
        });
    }

    private Future<MessageConsumer> getOrCreateMappingAndDelegatingCommandConsumer(final String tenantId) {
        final Future<MessageConsumer> messageConsumerFuture = connection.isConnected(getDefaultConnectionCheckTimeout())
                .compose(v -> connection.executeOnContext(result -> {
                    mappingAndDelegatingCommandConsumerFactory.getOrCreateClient(tenantId,
                            () -> newMappingAndDelegatingCommandConsumer(tenantId),
                            result);
                }));
        return messageConsumerFuture.recover(thr -> {
            log.debug("failed to create mappingAndDelegatingCommandConsumer for tenant {}", tenantId, thr);
            return Future.failedFuture(thr);
        });
    }

    private Future<MessageConsumer> newMappingAndDelegatingCommandConsumer(final String tenantId) {
        log.trace("creating new MappingAndDelegatingCommandConsumer [tenant-id: {}]", tenantId);
        final String address = AddressHelper.getTargetAddress(CommandConstants.NORTHBOUND_COMMAND_REQUEST_ENDPOINT, tenantId, null, connection.getConfig());
        return connection.createReceiver(
                address,
                ProtonQoS.AT_LEAST_ONCE,
                (delivery, message) -> {
                    mappingAndDelegatingCommandHandler.mapAndDelegateIncomingCommandMessage(tenantId, delivery, message);
                },
                connection.getConfig().getInitialCredits(),
                false, // no auto-accept
                sourceAddress -> { // remote close hook
                    log.debug("MappingAndDelegatingCommandConsumer receiver link [tenant-id: {}] closed remotely", tenantId);
                    mappingAndDelegatingCommandConsumerFactory.removeClient(tenantId);
                    invokeRecreateConsumersWithDelay();
                }).map(receiver -> {
                    log.debug("successfully created MappingAndDelegatingCommandConsumer [{}]", address);
                    consumerLinkTenants.add(tenantId);
                    final CommandConsumer consumer = new CommandConsumer(connection, receiver);
                    consumer.setLocalCloseHandler(sourceAddress -> {
                        log.debug("MappingAndDelegatingCommandConsumer receiver link [tenant-id: {}] closed locally", tenantId);
                        mappingAndDelegatingCommandConsumerFactory.removeClient(tenantId);
                        consumerLinkTenants.remove(tenantId);
                    });
                    return (MessageConsumer) consumer;
                }).recover(t -> {
                    log.debug("failed to create MappingAndDelegatingCommandConsumer [tenant-id: {}]", tenantId, t);
                    return Future.failedFuture(t);
                });
    }

    private void recreateConsumers() {
        if (recreatingConsumers.compareAndSet(false, true)) {
            log.debug("recreate command consumer links");
            connection.isConnected(getDefaultConnectionCheckTimeout())
                    .compose(res -> {
                        @SuppressWarnings("rawtypes")
                        final List<Future> consumerCreationFutures = new ArrayList<>();
                        // recreate mappingAndDelegatingCommandConsumers
                        consumerLinkTenants.forEach(tenantId -> {
                            log.debug("recreate command consumer link for tenant {}", tenantId);
                            consumerCreationFutures.add(getOrCreateMappingAndDelegatingCommandConsumer(tenantId));
                        });
                        return CompositeFuture.join(consumerCreationFutures);
                    }).onComplete(ar -> {
                        recreatingConsumers.set(false);
                        if (tryAgainRecreatingConsumers.compareAndSet(true, false) || ar.failed()) {
                            if (ar.succeeded()) {
                                // tryAgainRecreatingConsumers was set - try again immediately
                                recreateConsumers();
                            } else {
                                invokeRecreateConsumersWithDelay();
                            }
                        }
                    });
        } else {
            // if recreateConsumers() was triggered by a remote link closing, that might have occurred after that link was dealt with above;
            // therefore be sure recreateConsumers() gets called again once the current invocation has finished.
            log.debug("already recreating consumers");
            tryAgainRecreatingConsumers.set(true);
        }
    }

    private void invokeRecreateConsumersWithDelay() {
        connection.getVertx().setTimer(RECREATE_CONSUMERS_DELAY, tid -> recreateConsumers());
    }

// TODO implement the equivalent of a tenant timeout mechanism as used in the protocol adapters in order to close unused tenant-scoped receiver links
//    private void handleTenantTimeout(final Message<String> msg) {
//        final String tenantId = msg.body();
//        final MessageConsumer consumer = mappingAndDelegatingCommandConsumerFactory.getClient(tenantId);
//        if (consumer != null) {
//            log.info("timeout of tenant {}: closing and removing command consumer", tenantId);
//            consumer.close(v -> {
//                mappingAndDelegatingCommandConsumerFactory.removeClient(tenantId);
//                consumerLinkTenants.remove(tenantId);
//            });
//        }
//    }

}
