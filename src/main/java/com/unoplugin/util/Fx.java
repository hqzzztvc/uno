package com.unoplugin.util;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Every sound and particle the plugin plays, in one file.
 *
 * <p>The same reason {@link Settings} owns config and {@link Messages} owns text: "what does
 * this plugin sound like?" should be answerable by reading one class rather than grepping for
 * {@code playSound}. Call sites say <em>what happened</em> ({@link #cardPlayed}), never which
 * sound that is.
 *
 * <p>Two rules hold everywhere in here, because a casino table is somewhere a dozen players
 * stand around on a busy server:
 *
 * <ul>
 *   <li><strong>Bounded.</strong> No single effect spawns more than {@link #MAX_PARTICLES}
 *       particles, and nothing loops. A flourish costs a fixed packet or two, whatever is
 *       going on at the table.</li>
 *   <li><strong>Optional.</strong> {@code effects.sounds} / {@code effects.particles} in
 *       config.yml turn each half off outright, and {@code effects.volume} scales the rest,
 *       so an admin who finds it noisy has a dial instead of a reason to uninstall.</li>
 * </ul>
 *
 * <p>Particles go through {@link World#spawnParticle} rather than per-player sends: the server
 * already limits those packets to players in range of the spot, which is exactly the audience
 * a table flourish wants.
 */
public final class Fx {

    /** Nothing here is ever worth a lag spike, so every burst is clamped to this. */
    private static final int MAX_PARTICLES = 60;

    private final Settings settings;

    public Fx(Settings settings) {
        this.settings = settings;
    }

    // ------------------------------------------------------------------ the game

    /** A hand has been dealt: the table wakes up. */
    public void deal(Location table) {
        at(table, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.8f, 0.7f);
        at(table, Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.6f);
        ring(table, Particle.END_ROD, 0.55, 16);
    }

    /** A card lands on the discard pile, in its own colour. */
    public void cardPlayed(Location pile, Color colour) {
        at(pile, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.7f, 1.5f);
        at(pile, Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.1f);
        dust(pile, colour, 12, 0.14, 0.9f);
    }

    /** A card comes off the draw pile. The drawer hears it up close; the table hears the flick. */
    public void cardDrawn(Player drawer, Location pile) {
        at(pile, Sound.ITEM_BOOK_PAGE_TURN, 0.5f, 0.9f);
        burst(pile, Particle.CLOUD, 5, 0.08, 0.01);
        ui(drawer, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.4f);
    }

    /** The deck had nothing left to give — a flat, empty thunk rather than a draw. */
    public void deckEmpty(Location pile) {
        at(pile, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.5f);
        burst(pile, Particle.SMOKE, 6, 0.1, 0.01);
    }

    /** Someone was skipped. */
    public void skip(Location victim) {
        at(victim, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        burst(above(victim, 1.2), Particle.CRIT, 14, 0.3, 0.05);
    }

    /** The direction flipped. */
    public void reverse(Location table) {
        at(table, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 0.8f);
        ring(above(table, 0.4), Particle.ENCHANT, 0.7, 20);
    }

    /** A +2 or +4 landed on somebody. {@code cards} scales how nasty it looks. */
    public void penalty(Location victim, int cards) {
        Location head = above(victim, 1.3);
        if (cards >= 4) {
            at(victim, Sound.ENTITY_WITHER_SHOOT, 0.7f, 1.4f);
            burst(head, Particle.SOUL_FIRE_FLAME, 16, 0.3, 0.02);
        } else {
            at(victim, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.7f);
            burst(head, Particle.SMOKE, 12, 0.25, 0.02);
        }
    }

    /** A wild took the table to a new colour. */
    public void wild(Location pile, Color colour) {
        at(pile, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.3f);
        dust(above(pile, 0.3), colour, 24, 0.25, 1.1f);
    }

    /** Down to one card. The whole table should hear this one. */
    public void uno(Player caller, Location table) {
        at(table, Sound.ENTITY_PLAYER_LEVELUP, 0.9f, 1.6f);
        at(table, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 2.0f);
        if (caller != null) {
            burst(above(caller.getLocation(), 1.6), Particle.TOTEM_OF_UNDYING, 30, 0.4, 0.15);
        }
    }

    /** Somebody took the hand. */
    public void win(Location table, Player winner) {
        at(table, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        at(table, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 1.2f);
        celebrate(above(table, 1.0));
        if (winner != null) {
            celebrate(above(winner.getLocation(), 1.8));
        }
    }

    /** It is this player's move. Quiet — they hear it every single turn. */
    public void yourTurn(Player player) {
        ui(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.35f, 1.6f);
    }

    /** Whoever the table is waiting on, marked so everyone can see it. */
    public void turnMarker(Location player) {
        burst(above(player, 2.2), Particle.END_ROD, 8, 0.15, 0.01);
    }

    /** Their clock is running out. */
    public void turnWarning(Player player) {
        ui(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 0.7f);
    }

    /** That move wasn't legal. */
    public void denied(Player player) {
        ui(player, Sound.ENTITY_VILLAGER_NO, 0.5f, 1.2f);
    }

    // -------------------------------------------------------------------- the fan

    /** The highlight moved to another card. Pitch rises along the fan, so scrolling reads. */
    public void select(Player player, int index, int count) {
        float pitch = count <= 1 ? 1.4f : 1.0f + 0.8f * index / (count - 1);
        ui(player, Sound.UI_BUTTON_CLICK, 0.22f, pitch);
    }

    /** Already at the end of the fan — a duller tick, so a dead key still answers. */
    public void selectBlocked(Player player) {
        ui(player, Sound.UI_BUTTON_CLICK, 0.12f, 0.6f);
    }

    // ----------------------------------------------------------------- the wager

    /** Items went into the pot. */
    public void staked(Location table) {
        at(table, Sound.ENTITY_ITEM_PICKUP, 0.7f, 0.8f);
        burst(above(table, 0.4), Particle.HAPPY_VILLAGER, 8, 0.25, 0.02);
    }

    /** The pot changes hands. */
    public void jackpot(Location table, Player winner) {
        at(table, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
        celebrate(above(table, 0.8));
        if (winner != null) {
            ui(winner, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        }
    }

    /** The winner left it all on the table for another hand. */
    public void letItRide(Location table) {
        at(table, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.4f);
        at(table, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.6f, 1.2f);
        ring(above(table, 0.5), Particle.FLAME, 0.75, 24);
    }

    // ------------------------------------------------------------------- tables

    /** A table was placed in the world. */
    public void tablePlaced(Location table) {
        at(table, Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 0.9f, 0.8f);
        at(table, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.8f);
        ring(table, Particle.END_ROD, 0.8, 20);
    }

    /** Somebody sat down at (or got up from) a stool. */
    public void seat(Player player, boolean sitting) {
        ui(player, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, sitting ? 1.2f : 0.9f);
    }

    // ---------------------------------------------------------------- primitives

    /** A sound for one player only, at their own position — UI feedback, not a table noise. */
    public void ui(Player player, Sound sound, float volume, float pitch) {
        if (player == null || !settings.sounds()) {
            return;
        }
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS,
                volume * settings.effectVolume(), pitch);
    }

    /** A sound everyone near this spot hears. */
    public void at(Location loc, Sound sound, float volume, float pitch) {
        if (loc == null || loc.getWorld() == null || !settings.sounds()) {
            return;
        }
        loc.getWorld().playSound(loc, sound, SoundCategory.PLAYERS,
                volume * settings.effectVolume(), pitch);
    }

    /** A puff of dust in an exact colour — how a card's own colour gets on screen. */
    public void dust(Location loc, Color colour, int count, double spread, float size) {
        if (colour == null) {
            burst(loc, Particle.CLOUD, count, spread, 0.01);
            return;
        }
        spawn(loc, Particle.DUST, count, spread, 0.0, new Particle.DustOptions(colour, size));
    }

    /** A burst of one particle type, scattered over {@code spread} blocks. */
    public void burst(Location loc, Particle particle, int count, double spread, double speed) {
        spawn(loc, particle, count, spread, speed, null);
    }

    /** A flat ring of particles — the flourish that reads as "something happened here". */
    public void ring(Location centre, Particle particle, double radius, int points) {
        if (centre == null || centre.getWorld() == null || !settings.particles()) {
            return;
        }
        World world = centre.getWorld();
        int n = Math.min(points, MAX_PARTICLES);
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            world.spawnParticle(particle, centre.getX() + Math.cos(a) * radius, centre.getY(),
                    centre.getZ() + Math.sin(a) * radius, 1, 0, 0, 0, 0);
        }
    }

    /** A firework burst without a firework: no entity spawned, so nothing to clean up. */
    public void celebrate(Location loc) {
        burst(loc, Particle.FIREWORK, 40, 0.5, 0.12);
        burst(loc, Particle.ELECTRIC_SPARK, 20, 0.4, 0.08);
    }

    /**
     * {@code loc} raised by {@code dy}, or null if there is nowhere to put the effect.
     *
     * <p>A caller can legitimately have nothing to point at — a bot has no location, and a
     * table can go with the chunk mid-hand — and an effect is never worth an exception, so
     * null flows on down to the primitives, which skip.
     */
    private static Location above(Location loc, double dy) {
        return loc == null ? null : loc.clone().add(0, dy, 0);
    }

    private <T> void spawn(Location loc, Particle particle, int count, double spread,
                           double speed, T data) {
        if (loc == null || loc.getWorld() == null || !settings.particles()) {
            return;
        }
        loc.getWorld().spawnParticle(particle, loc, Math.min(count, MAX_PARTICLES),
                spread, spread, spread, speed, data);
    }
}
