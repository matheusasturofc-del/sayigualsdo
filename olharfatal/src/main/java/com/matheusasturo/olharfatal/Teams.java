package com.matheusasturo.olharfatal;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/**
 * Os dois times usam o placar (scoreboard) do vanilla, então também funcionam com
 * {@code /team join View <jogador>} e {@code /team join Kill <jogador>} e ficam salvos no mundo.
 * A cor do time Kill (vermelho) é o que deixa o contorno do efeito brilhante vermelho.
 */
public final class Teams {

    public enum Role {
        VIEW("View", ChatFormatting.AQUA),
        KILL("Kill", ChatFormatting.RED),
        NONE("", ChatFormatting.WHITE);

        public final String teamName;
        public final ChatFormatting color;

        Role(String teamName, ChatFormatting color) {
            this.teamName = teamName;
            this.color = color;
        }
    }

    private Teams() {
    }

    /** Cria os times View e Kill (se ainda não existirem) e garante as cores. */
    public static void ensure(MinecraftServer server) {
        team(server, Role.VIEW);
        team(server, Role.KILL);
    }

    public static PlayerTeam team(MinecraftServer server, Role role) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(role.teamName);
        if (team == null) {
            team = scoreboard.addPlayerTeam(role.teamName);
            team.setDisplayName(Component.literal(role.teamName).withStyle(role.color));
            team.setPrefix(Component.literal("[" + role.teamName + "] ").withStyle(role.color));
        }
        if (team.getColor() != role.color) {
            team.setColor(role.color);
        }
        return team;
    }

    public static Role role(ServerPlayer player) {
        Team team = player.getTeam();
        if (team == null) {
            return Role.NONE;
        }
        if (team.getName().equalsIgnoreCase(Role.VIEW.teamName)) {
            return Role.VIEW;
        }
        if (team.getName().equalsIgnoreCase(Role.KILL.teamName)) {
            return Role.KILL;
        }
        return Role.NONE;
    }

    /** Coloca o jogador no time (ou tira de todos, com {@link Role#NONE}). */
    public static void set(MinecraftServer server, ServerPlayer player, Role role) {
        Scoreboard scoreboard = server.getScoreboard();
        String name = player.getScoreboardName();
        if (role == Role.NONE) {
            scoreboard.removePlayerFromTeam(name);
        } else {
            scoreboard.addPlayerToTeam(name, team(server, role));
        }
    }
}
