package connection;

public class ChatUserRecord {
    private final UserConnection userConnection;

    private final String username;

    public ChatUserRecord(UserConnection userConnection, String username) {
        this.userConnection = userConnection;
        this.username = username;
    }

    public UserConnection getUserConnection() {
        return userConnection;
    }

    public String getUsername() {
        return username;
    }
}
