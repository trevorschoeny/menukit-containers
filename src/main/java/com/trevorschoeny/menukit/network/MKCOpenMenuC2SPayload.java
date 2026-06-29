package com.trevorschoeny.menukit.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import org.jetbrains.annotations.ApiStatus;

/**
 * The one library-owned generic "open my menu" Client → Server request. Carries
 * only the {@link Identifier} of the {@code MKCMenu} the client wants opened; the
 * server-side receiver (registered once in {@code MKC.init()}) looks the menu up
 * by that id, hops to the main thread, and calls {@code MKCMenu.open(player)}.
 *
 * <p>This replaces the per-consumer hand-rolled open payload that every custom
 * menu used to ship: one generic payload + one generic receiver serves <em>every</em>
 * {@code MKCMenu} a consumer defines, keyed by the menu's registered id. An unknown
 * id is a fail-loud log on the receiver side, never an NPE.
 */
@ApiStatus.Internal
public record MKCOpenMenuC2SPayload(Identifier menuId) implements CustomPacketPayload {

    public static final Type<MKCOpenMenuC2SPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath("menukit", "open_menu_c2s"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MKCOpenMenuC2SPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Identifier.STREAM_CODEC, MKCOpenMenuC2SPayload::menuId,
                    MKCOpenMenuC2SPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
