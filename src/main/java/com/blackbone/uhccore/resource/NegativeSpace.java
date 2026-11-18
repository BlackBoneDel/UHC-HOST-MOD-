package com.blackbone.uhccore.resource;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/** Font advances F801–F817 = -1..-23px, F818–F82F = +1..+24px. */
public final class NegativeSpace {
    private static final Key FONT = Key.key("uhccore", "negative_space");
    private NegativeSpace() { }

    public static Component shift(int pixels) {
        StringBuilder glyphs = new StringBuilder();
        int remaining = Math.clamp(pixels, -512, 512);
        while (remaining < 0) {
            int step = Math.min(-remaining, 23);
            glyphs.append((char) (0xF800 + step));
            remaining += step;
        }
        while (remaining > 0) {
            int step = Math.min(remaining, 24);
            glyphs.append((char) (0xF817 + step));
            remaining -= step;
        }
        return Component.text(glyphs.toString()).font(FONT);
    }
}
