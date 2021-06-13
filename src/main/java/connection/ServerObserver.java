package connection;

@FunctionalInterface
public interface ServerObserver {
    void update(Message username);
}
