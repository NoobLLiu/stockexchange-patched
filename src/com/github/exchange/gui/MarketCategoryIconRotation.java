package com.github.exchange.gui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Stateless helpers for the rotating icons of aggregate sell categories.
 */
public final class MarketCategoryIconRotation {
    private MarketCategoryIconRotation() {
    }

    public static List<String> distinctStableIconKeys(Collection<String> iconKeys) {
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        if (iconKeys != null) {
            for (String iconKey : iconKeys) {
                if (iconKey != null && !iconKey.isBlank()) {
                    unique.add(iconKey);
                }
            }
        }
        return new ArrayList<String>(unique);
    }

    public static int indexForSecond(long elapsedSeconds, int iconCount) {
        if (iconCount <= 0) {
            return -1;
        }
        return (int)Math.floorMod(elapsedSeconds, (long)iconCount);
    }
}
