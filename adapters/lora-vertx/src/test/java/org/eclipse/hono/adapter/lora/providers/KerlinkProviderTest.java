/*******************************************************************************
 * Copyright (c) 2019, 2020 Contributors to the Eclipse Foundation
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

package org.eclipse.hono.adapter.lora.providers;

import org.junit.jupiter.api.extension.ExtendWith;

import io.vertx.junit5.VertxExtension;

/**
 * Verifies the behavior of {@link KerlinkProvider}.
 */
@ExtendWith(VertxExtension.class)
public class KerlinkProviderTest extends LoraProviderTestBase<KerlinkProvider> {

    /**
     * {@inheritDoc}
     */
    @Override
    protected KerlinkProvider newProvider() {
        return new KerlinkProvider();
    }
}
