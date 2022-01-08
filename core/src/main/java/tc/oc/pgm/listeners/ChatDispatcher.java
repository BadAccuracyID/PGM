package tc.oc.pgm.listeners;

import static net.kyori.adventure.identity.Identity.identity;
import static net.kyori.adventure.key.Key.key;
import static net.kyori.adventure.sound.Sound.sound;
import static net.kyori.adventure.text.Component.text;
import static tc.oc.pgm.util.text.TextTranslations.translate;

import app.ashcon.intake.Command;
import app.ashcon.intake.parametric.annotation.Text;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.Permissions;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchManager;
import tc.oc.pgm.api.party.Party;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.setting.SettingKey;
import tc.oc.pgm.api.setting.SettingValue;
import tc.oc.pgm.ffa.Tribute;
import tc.oc.pgm.util.Audience;
import tc.oc.pgm.util.StringUtils;
import tc.oc.pgm.util.UsernameFormatUtils;
import tc.oc.pgm.util.named.NameStyle;
import tc.oc.pgm.util.text.TextTranslations;

public class ChatDispatcher implements Listener {

  private static final ChatDispatcher INSTANCE = new ChatDispatcher();

  public static ChatDispatcher get() {
    return INSTANCE; // FIXME: no one should need to statically access ChatDispatcher, but community
    // does this a lot
  }

  private final MatchManager manager;

  public static final TextComponent ADMIN_CHAT_PREFIX =
      text()
          .append(text("[", NamedTextColor.WHITE))
          .append(text("A", NamedTextColor.GOLD))
          .append(text("] ", NamedTextColor.WHITE))
          .build();

  private static final Sound AC_SOUND = sound(key("random.orb"), Sound.Source.MASTER, 1f, 0.7f);

  private static final String GLOBAL_SYMBOL = "!";

  private static final String GLOBAL_FORMAT = "%s: %s";
  private static final String PREFIX_FORMAT = "%s: %s";

  private static final Predicate<MatchPlayer> AC_FILTER =
      viewer -> viewer.getBukkit().hasPermission(Permissions.ADMINCHAT);

  public ChatDispatcher() {
    this.manager = PGM.get().getMatchManager();
    PGM.get().getServer().getPluginManager().registerEvents(this, PGM.get());
  }

  @Command(
      aliases = {"g", "all"},
      desc = "Send a message to everyone",
      usage = "[message]")
  public void onGlobalChat(
      Match match, MatchPlayer sender, @Nullable @Text String message) {
    this.sendGlobal(match, sender, message, true);
  }

  public void sendGlobal(
          Match match, MatchPlayer sender, @Nullable @Text String message, boolean shoutPrefix) {
    final Party party = sender == null ? match.getDefaultParty() : sender.getParty();

    String format;
    if (shoutPrefix) {
      format =
              LegacyComponentSerializer.legacySection().serialize(text("§6[SHOUT] "))
                      + TextTranslations.translateLegacy(party.getChatPrefix(), null)
                      + GLOBAL_FORMAT;
    } else {
      format = TextTranslations.translateLegacy(party.getChatPrefix(), null) + GLOBAL_FORMAT;
    }

    send(
            match,
            sender,
            message,
            format,
            getChatFormat(null, sender, message),
            viewer -> true,
            SettingValue.CHAT_GLOBAL);
  }

  @Command(
      aliases = {"t"},
      desc = "Send a message to your team",
      usage = "[message]")
  public void onTeamChat(Match match, MatchPlayer sender, @Nullable @Text String message) {
    this.sendTeam(match, sender, message);
  }

  public void sendTeam(Match match, MatchPlayer sender, @Nullable @Text String message) {
    final Party party = sender == null ? match.getDefaultParty() : sender.getParty();

    // No team chat when playing free-for-all or match end, default to global chat
    if (party instanceof Tribute || match.isFinished()) {
      sendGlobal(match, sender, message, party instanceof Tribute);
      return;
    }

    send(
            match,
            sender,
            message,
            TextTranslations.translateLegacy(party.getChatPrefix(), null) + PREFIX_FORMAT,
            getChatFormat(party.getChatPrefix(), sender, message),
            viewer ->
                    party.equals(viewer.getParty())
                            || (viewer.isObserving()
                            && viewer.getBukkit().hasPermission(Permissions.ADMINCHAT)),
            SettingValue.CHAT_TEAM);
  }

  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void onChat(AsyncPlayerChatEvent event) {
    if (CHAT_EVENT_CACHE.getIfPresent(event) == null) {
      event.setCancelled(true);
    } else {
      CHAT_EVENT_CACHE.invalidate(event);
      return;
    }

    final MatchPlayer player = manager.getPlayer(event.getPlayer());
    if (player != null) {
      final String message = event.getMessage();

      if (message.startsWith(GLOBAL_SYMBOL)) {
        sendGlobal(player.getMatch(), player, message.substring(1), true);
      } else {
        sendDefault(player.getMatch(), player, event.getMessage());
      }
    }
  }

  public void sendDefault(Match match, MatchPlayer sender, String message) {
    if ((sender == null ? SettingValue.CHAT_GLOBAL : sender.getSettings().getValue(SettingKey.CHAT))
        == SettingValue.CHAT_TEAM) {
      sendTeam(match, sender, message);
    } else {
      sendGlobal(match, sender, message, true);
    }
  }

  public void playSound(MatchPlayer player, Sound sound) {
    SettingValue value = player.getSettings().getValue(SettingKey.SOUNDS);
    if ((sound.equals(AC_SOUND) && value.equals(SettingValue.SOUNDS_ALL))) {
      player.playSound(sound);
    }
  }

  private static final Cache<AsyncPlayerChatEvent, Boolean> CHAT_EVENT_CACHE =
      CacheBuilder.newBuilder().weakKeys().expireAfterWrite(15, TimeUnit.SECONDS).build();

  public void send(
      Match match,
      MatchPlayer sender,
      @Nullable String text,
      String format,
      Component componentMsg,
      Predicate<MatchPlayer> filter,
      @Nullable SettingValue type) {
    // When a message is empty, this indicates the player wants to change their default chat channel
    if ((text == null || text.isEmpty()) && sender != null) {
      // FIXME: there should be a better way to do this
      PGM.get()
          .getExecutor()
          .schedule(
              () ->
                  sender
                      .getBukkit()
                      .performCommand("set " + SettingKey.CHAT + " " + type.getName()),
              50,
              TimeUnit.MILLISECONDS); // Run sync to stop console spam
      return;
    }

    final String message = text.trim();

    if (sender != null) {
      PGM.get()
          .getAsyncExecutor()
          .execute(
              () -> {
                final AsyncPlayerChatEvent event =
                    new AsyncPlayerChatEvent(
                        false,
                        sender.getBukkit(),
                        message,
                        match.getPlayers().stream()
                            .filter(filter)
                            .map(MatchPlayer::getBukkit)
                            .collect(Collectors.toSet()));
                event.setFormat(format);
                CHAT_EVENT_CACHE.put(event, true);
                match.callEvent(event);

                if (event.isCancelled()) {
                  return;
                }

                event.getRecipients().stream()
                    .map(Audience::get)
                    .forEach(player -> player.sendMessage(identity(sender.getId()), componentMsg));
              });
      return;
    }
    match.getPlayers().stream()
        .filter(filter)
        .forEach(
            player ->
                player.sendMessage(
                    text(
                        String.format(
                            format,
                            translate(
                                UsernameFormatUtils.CONSOLE_NAME,
                                TextTranslations.getLocale(player.getBukkit())),
                            message))));
  }

  private MatchPlayer getApproximatePlayer(Match match, String query, CommandSender sender) {
    return StringUtils.bestFuzzyMatch(
        query,
        match.getPlayers().stream()
            .collect(Collectors.toMap(player -> player.getBukkit().getName(), Function.identity())),
        0.75);
  }

  public static void broadcastAdminChatMessage(Component message, Match match) {
    broadcastAdminChatMessage(message, match, Optional.empty());
  }

  public static void broadcastAdminChatMessage(
      Component message, Match match, Optional<Sound> sound) {
    TextComponent formatted = ADMIN_CHAT_PREFIX.append(message);
    match.getPlayers().stream()
        .filter(AC_FILTER)
        .forEach(
            mp -> {
              // If provided a sound, play if setting allows
              sound.ifPresent(
                  s -> {
                    if (canPlaySound(mp)) {
                      mp.playSound(s);
                    }
                  });
              mp.sendMessage(formatted);
            });
    Audience.console().sendMessage(formatted);
  }

  private static boolean canPlaySound(MatchPlayer viewer) {
    return viewer.getSettings().getValue(SettingKey.SOUNDS).equals(SettingValue.SOUNDS_ALL);
  }

  private Component getChatFormat(@Nullable Component prefix, MatchPlayer player, String message) {
    Component msg = text(message != null ? message : "");
    if (prefix == null)
      return text()
          .append(text("[SHOUT] ", NamedTextColor.GOLD))
          .append(player.getName(NameStyle.VERBOSE))
          .append(text(": ", NamedTextColor.WHITE))
          .append(msg)
          .build();
    return text()
        .append(prefix)
        .append(player.getName(NameStyle.VERBOSE))
        .append(text(": ", NamedTextColor.WHITE))
        .append(msg)
        .build();
  }
}
