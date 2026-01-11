package de.maxhenkel.voicechat.logging;

public class SystemOutLogger implements VoiceChatLogger {

    private static final String PLACEHOLDER = "{}";
    private final boolean debugMode;

    public SystemOutLogger() {
        this.debugMode = System.getProperty("voicechat.debug") != null;
    }

    @Override
    public void log(LogLevel level, String message, Object... args) {
        if (!isEnabled(level)) {
            return;
        }
        Throwable throwable = null;
        if (args.length > 0 && args[args.length - 1] instanceof Throwable) {
            throwable = (Throwable) args[args.length - 1];
            Object[] newArgs = new Object[args.length - 1];
            System.arraycopy(args, 0, newArgs, 0, newArgs.length);
            args = newArgs;
        }
        String formatted = replacePlaceholders(message, args);
        String line = String.format("[%s] %s", level.name(), formatted);
        if (level == LogLevel.ERROR || level == LogLevel.FATAL || level == LogLevel.WARN) {
            System.err.println(line);
            if (throwable != null) {
                throwable.printStackTrace(System.err);
            }
        } else {
            System.out.println(line);
            if (throwable != null) {
                throwable.printStackTrace(System.out);
            }
        }
    }

    @Override
    public boolean isEnabled(LogLevel level) {
        if (debugMode) {
            return true;
        }
        return level != LogLevel.TRACE;
    }

    private String replacePlaceholders(String message, Object... args) {
        StringBuilder formattedMessage = new StringBuilder();
        int argIndex = 0;
        int placeholderStart = message.indexOf(PLACEHOLDER);

        while (placeholderStart >= 0 && argIndex < args.length) {
            formattedMessage.append(message, 0, placeholderStart);
            formattedMessage.append(args[argIndex]);
            message = message.substring(placeholderStart + PLACEHOLDER.length());
            argIndex++;
            placeholderStart = message.indexOf(PLACEHOLDER);
        }

        formattedMessage.append(message);
        return formattedMessage.toString();
    }
}
