package at.helpch.chatchat.listener;

import at.helpch.chatchat.ChatChatPlugin;
import at.helpch.chatchat.api.user.ChatUser;
import at.helpch.chatchat.user.ConsoleUser;
import at.helpch.chatchat.channel.ChatChannel;
import at.helpch.chatchat.util.ChannelUtils;
import at.helpch.chatchat.util.FormatUtils;
import at.helpch.chatchat.util.MessageProcessor;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UnknownFormatConversionException;
import java.util.regex.Pattern;

public final class ChatListener implements Listener {

    private static final Pattern LEGACY_FORMATS_PATTERN = Pattern.compile("§[\\da-fk-or]");
    private static final Pattern LEGACY_HEX_COLOR_PATTERN = Pattern.compile("§x(§[\\da-fA-F]){6}");
    private final ChatChatPlugin plugin;

    public ChatListener(@NotNull final ChatChatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        // Clear recipients to handle them through our custom system
        try {
            event.viewers().clear();
        } catch (UnsupportedOperationException ignored) {
            // a plugin is doing something weird so all we can do is cancel
            event.setCancelled(true);
            return;
        }

        // Convert Component message to String for processing
        final var originalMessage = PlainTextComponentSerializer.plainText().serialize(event.message());
        final var cleanedMessage = cleanseMessage(originalMessage);
        
        final var player = event.getPlayer();
        final var user = (ChatUser) plugin.usersHolder().getUser(player);

        if (!user.chatEnabled()) {
            event.setCancelled(true);
            user.sendMessage(plugin.configManager().messages().chatDisabled());
            return;
        }

        final var channelByPrefix =
            ChannelUtils.findChannelByPrefix(
                List.copyOf(plugin.configManager().channels().channels().values()),
                cleanedMessage);

        final var message = channelByPrefix.isEmpty() || !channelByPrefix.get().isUsableBy(user)
            ? cleanedMessage
            : cleanedMessage.replaceFirst(Pattern.quote(channelByPrefix.get().messagePrefix()), "");

        var channel = channelByPrefix.isEmpty() || !channelByPrefix.get().isUsableBy(user)
            ? user.channel()
            : channelByPrefix.get();

        // Ensure the user still has the channel permission, if not reset them back to the default channel
        if (!channel.isUsableBy(user)) {
            event.setCancelled(true);

            user.channel(ChatChannel.defaultChannel());
            user.sendMessage(plugin.configManager().messages().channelNoPermissionSwitch()
                .replaceText(builder -> builder.matchLiteral("<default>").replacement(ChatChannel.defaultChannel().name())));
            return;
        }

        final var consoleFormat = plugin.configManager().formats().consoleFormat();

        final var oldChannel = user.channel();

        // We switch the user to the channel here so that the console can parse the correct channel prefix
        user.channel(channel);

        // Update the event message with processed content
        event.message(LegacyComponentSerializer.legacySection().deserialize(
            LegacyComponentSerializer.legacySection().serialize(
                MessageProcessor.processMessage(plugin, user, ConsoleUser.INSTANCE, message)
            )
        ));

        try {
            // Set the renderer for console formatting
            final var formatComponent = FormatUtils.parseConsoleFormat(consoleFormat, player);
            final var formatString = LegacyComponentSerializer.legacySection().serialize(formatComponent);
            
            event.renderer((source, sourceDisplayName, eventMessage, viewer) -> {
                final var messageString = LegacyComponentSerializer.legacySection().serialize(eventMessage);
                // Use String.format to insert the message into the format
                final var formattedString = String.format(formatString, player.getName(), messageString);
                return LegacyComponentSerializer.legacySection().deserialize(formattedString);
            });
        } catch (UnknownFormatConversionException exception) {
            plugin.getLogger().severe(
                "Your console format contains illegal characters: '%" +
                    exception
                        .getMessage()
                        .replace("Conversion = ", "")
                        .replace("'", "") +
                    "'. You cannot use the % symbol in your console format.");
            plugin.getLogger().severe(
                "Make sure that all the PlaceholderAPI expansions for the placeholders you use in your console " +
                    "format are installed and work properly.");
        }

        var sent = MessageProcessor.process(plugin, user, channel, message, event.isAsynchronous());
        // Cancel the event if the message doesn't end up being sent
        // This only happens if the message contains illegal characters or if the ChatChatEvent is canceled.
        if (!event.isCancelled() && !sent) {
            event.setCancelled(true);
        }
        user.channel(oldChannel);
    }

    private static String cleanseMessage(@NotNull final String message) {
        return LEGACY_FORMATS_PATTERN.matcher(
            LEGACY_HEX_COLOR_PATTERN.matcher(message).replaceAll("")
        ).replaceAll("").replace("§", "");
    }

}
