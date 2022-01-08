package tc.oc.pgm.community.command;

import static net.kyori.adventure.text.Component.*;

import app.ashcon.intake.Command;
import app.ashcon.intake.CommandException;
import app.ashcon.intake.parametric.annotation.Switch;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.Permissions;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.community.modules.FreezeMatchModule;
import tc.oc.pgm.util.Audience;
import tc.oc.pgm.util.named.NameStyle;

public class ModerationCommand implements Listener {

  public ModerationCommand() {
    PGM.get().getServer().getPluginManager().registerEvents(this, PGM.get());
  }

  @Command(
      aliases = {"frozenlist", "fls", "flist"},
      desc = "View a list of frozen players",
      perms = Permissions.FREEZE)
  public void sendFrozenList(Audience sender, Match match) {
    FreezeMatchModule fmm = match.getModule(FreezeMatchModule.class);

    if (fmm.getFrozenPlayers().isEmpty() && fmm.getOfflineFrozenCount() < 1) {
      sender.sendWarning(translatable("moderation.freeze.frozenList.none"));
      return;
    }

    // Online Players
    if (!fmm.getFrozenPlayers().isEmpty()) {
      Component names =
          join(
              text(", ", NamedTextColor.GRAY),
              fmm.getFrozenPlayers().stream()
                  .map(m -> m.getName(NameStyle.FANCY))
                  .collect(Collectors.toList()));
      sender.sendMessage(
          formatFrozenList(
              "moderation.freeze.frozenList.online", fmm.getFrozenPlayers().size(), names));
    }

    // Offline Players
    if (fmm.getOfflineFrozenCount() > 0) {
      Component names = fmm.getOfflineFrozenNames();
      sender.sendMessage(
          formatFrozenList(
              "moderation.freeze.frozenList.offline", fmm.getOfflineFrozenCount(), names));
    }
  }

  private Component formatFrozenList(String key, int count, Component names) {
    return translatable(key, NamedTextColor.GRAY, text(count, NamedTextColor.AQUA), names);
  }

  @Command(
      aliases = {"freeze", "fz", "f", "ss"},
      usage = "<player>",
      flags = "s",
      desc = "Toggle a player's frozen state",
      perms = Permissions.FREEZE)
  public void freeze(CommandSender sender, Match match, Player target, @Switch('s') boolean silent)
      throws CommandException {
    setFreeze(sender, match, target, checkSilent(silent, sender));
  }

  private void setFreeze(CommandSender sender, Match match, Player target, boolean silent) {
    FreezeMatchModule fmm = match.getModule(FreezeMatchModule.class);
    MatchPlayer player = match.getPlayer(target);
    if (player != null) {
      fmm.setFrozen(sender, player, !fmm.isFrozen(player), silent);
    }
  }

  // Force vanished players to silent broadcast
  private boolean checkSilent(boolean silent, CommandSender sender) {
    if (!silent && sender instanceof Player && ((Player) sender).hasMetadata("vanished")) {
      silent = true;
    }
    return silent;
  }
}
