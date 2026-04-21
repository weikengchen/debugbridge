package com.debugbridge.fabric262;

import com.debugbridge.core.texture.ItemTextureProvider;

public class Minecraft262ItemTextureProvider implements ItemTextureProvider {
    @Override
    public TextureResult getItemTexture(int slot) {
        throw new UnsupportedOperationException("26.2 item texture provider not implemented");
    }

    @Override
    public TextureResult getEntityItemTexture(int entityId, String slot) {
        throw new UnsupportedOperationException("26.2 item texture provider not implemented");
    }

    @Override
    public TextureResult getItemTextureById(String itemId) {
        throw new UnsupportedOperationException("26.2 item texture provider not implemented");
    }
}
