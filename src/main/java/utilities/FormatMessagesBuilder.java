package utilities;

import java.text.SimpleDateFormat;
import java.util.Calendar;

public class FormatMessagesBuilder {
    public static String buildMessageWithDateNow(String message) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(Calendar.getInstance().getTime()) + " | " + message + "\n";
    }

    public static String buildDateNow() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z").format(Calendar.getInstance().getTime());
    }

    public static String buildChatTextAreaUserMessage(String username, String text) {
        return "[CLIENT] " + username + "\n" + text + "\n";
    }

    public static String buildChatTextAreaServiceMessage(String text) {
        return "[SERVER] SERVICE MESSAGE\n" + text + "\n";
    }
}
