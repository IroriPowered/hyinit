/*
 * Copyright 2016 FabricMC
 * Copyright 2026 IroriPowered
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
 *
 * Modified from Fabric Loader's net/fabricmc/loader/impl/launch/knot/MixinServiceKnotBootstrap.java for Hyinit.
 */
package cc.irori.hyinit.mixin;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public class HyinitMixinBootstrap implements IMixinServiceBootstrap {

    @Override
    public String getName() {
        return "Hyinit";
    }

    @Override
    public String getServiceClassName() {
        return "cc.irori.hyinit.mixin.HyinitMixinService";
    }

    @Override
    public void bootstrap() {
        // No-op
    }
}
