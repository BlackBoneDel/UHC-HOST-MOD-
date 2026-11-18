package com.blackbone.uhccore.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable schedule; validation prevents growing or overlapping borders during a ranked match. */
public final class BorderSchedule {
    private final int initialRadius;
    private final List<BorderPhase> phases;

    public BorderSchedule(int initialRadius, List<BorderPhase> phases) {
        if (initialRadius < 10) throw new IllegalArgumentException("World radius too small");
        this.initialRadius = initialRadius;
        this.phases = List.copyOf(phases.stream().sorted(Comparator.comparingInt(BorderPhase::startSeconds)).toList());
        int previousRadius = initialRadius;
        long previousEnd = 0;
        for (BorderPhase phase : this.phases) {
            if (phase.startSeconds() < previousEnd || phase.targetRadius() >= previousRadius) {
                throw new IllegalArgumentException("Border phases overlap or do not shrink");
            }
            previousRadius = phase.targetRadius();
            previousEnd = phase.endSeconds();
        }
    }

    public List<BorderPhase> phases() { return phases; }
    public int initialRadius() { return initialRadius; }

    public BorderSchedule withPhase(BorderPhase phase) {
        List<BorderPhase> copy = new ArrayList<>(phases);
        copy.add(phase);
        return new BorderSchedule(initialRadius, copy);
    }

    public BorderSchedule withoutPhase(int index) {
        if (index < 0 || index >= phases.size()) throw new IndexOutOfBoundsException(index);
        List<BorderPhase> copy = new ArrayList<>(phases);
        copy.remove(index);
        return new BorderSchedule(initialRadius, copy);
    }

    public int activeIndex(long elapsedSeconds) {
        int active = -1;
        for (int i = 0; i < phases.size(); i++) {
            if (elapsedSeconds < phases.get(i).startSeconds()) break;
            active = i;
        }
        return active;
    }

    public double radiusAt(long elapsedSeconds) {
        double previous = initialRadius;
        for (BorderPhase phase : phases) {
            if (elapsedSeconds < phase.startSeconds()) return previous;
            if (elapsedSeconds < phase.endSeconds()) {
                double progress = (elapsedSeconds - phase.startSeconds()) / (double) phase.durationSeconds();
                return previous + (phase.targetRadius() - previous) * progress;
            }
            previous = phase.targetRadius();
        }
        return previous;
    }

    public double progressAt(long elapsedSeconds) {
        int index = activeIndex(elapsedSeconds);
        if (index == -1) return 0;
        BorderPhase phase = phases.get(index);
        return Math.clamp((elapsedSeconds - phase.startSeconds()) / (double) phase.durationSeconds(), 0, 1);
    }
}
