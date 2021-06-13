package server;

import connection.*;
import utilities.FormatMessagesBuilder;

import java.io.IOException;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.*;

public class ServerController {
    private ServerSocket serverSocket;
    private ServerSwingView graphicView;
    private ServerModel serverModel;
    private SessionPasswordUpdater passwordUpdater;

    private volatile boolean hasServerStarted = false;

    private static final int PASSWORD_EXPIRATION_MILLIS_TIME = 100000;
    private static final int POOL_DELAY_SECS_TIME = 1;
    private static final int INITIAL_POOL_DELAY_SECS_TIME = 0;
    private static final int SCHEDULED_THREAD_POOL_CORE_SIZE = 10;

    private final List<ServerObserver> observers = new ArrayList<>();

    private final Map<Socket, ScheduledFuture<?>> scheduledActiveTasks = new HashMap<>();

    public synchronized void launch() {
        while (true) {
            try {
                while (!hasServerStarted) {
                    wait();
                }

                acceptNewUserConnections();
            } catch (InterruptedException e) {
                graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                        "Server main thread was stopped by interrupt"));
                graphicView.addServiceMessageToServerLogsTextArea(Arrays.toString(e.getStackTrace()));
            }
        }
    }

    public void addObserver(ServerObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(ServerObserver observer) {
        observers.remove(observer);
    }

    private void notifyObservers(Message message) {
        for (ServerObserver observer : observers) {
            observer.update(message);
        }
    }

    public void setHasServerStarted(boolean hasServerStarted) {
        this.hasServerStarted = hasServerStarted;
    }

    public boolean hasServerStarted() {
        return hasServerStarted;
    }

    public ServerModel getServerModel() {
        return serverModel;
    }

    public void setGraphicView(ServerSwingView graphicView) {
        this.graphicView = graphicView;
    }

    public void setServerModel(ServerModel serverModel) {
        this.serverModel = serverModel;
    }

    protected void startServerOnPort(int port) throws Exception {
        try {
            serverSocket = new ServerSocket(port);

            generateNewSessionPassword();
            passwordUpdater = new SessionPasswordUpdater();
            passwordUpdater.start();
            graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                    "Server has launched on port " + port));
        } catch (Exception exception) {
            graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                    "Couldn't launch the server"));
            throw exception;
        }
    }

    protected void stopServer() {
        String finalMessage = null;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                closeConnectionsWithAllUsers();
                serverSocket.close();
                passwordUpdater.interrupt();
                finalMessage = "Server was stopped";
            } else {
                finalMessage = "Invalid operation. Server is not running yet";
            }
        } catch (Exception exception) {
            finalMessage = "Couldn't stop the server. Try again...";
        } finally {
            graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(finalMessage));
        }
    }

    protected void generateNewSessionPassword() {
        if (hasServerStarted) {
            serverModel.updateCurrentSessionPassword();
            graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                    "Password for current session: " + serverModel.getCurrentSessionPassword()));
        } else {
            graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                    "Invalid operation. Server is not running yet"));
        }
    }

    protected String getCurrentSessionPassword() throws ConnectException {
        if (hasServerStarted) {
            return serverModel.getCurrentSessionPassword();
        } else {
            graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                    "Invalid operation. Server is not running yet"));
            throw new ConnectException();
        }
    }

    private void closeConnectionsWithAllUsers() throws IOException {
        Map<String, UserConnection> onlineUsersConnections = serverModel.getOnlineUsersConnections();
        for (UserConnection userConnection : onlineUsersConnections.values()) {
            userConnection.close();
        }
        for (String username : onlineUsersConnections.keySet()) {
            notifyObservers(new Message(MessageType.NOTIFY_REMOVE, username));
        }

        serverModel.getOnlineUsersConnections().clear();
        serverModel.getOnlineUsersMetaInfos().clear();
    }

    protected void acceptNewUserConnections() {
        ScheduledExecutorService executor = Executors.newScheduledThreadPool(SCHEDULED_THREAD_POOL_CORE_SIZE);

        while (true) {
            try {

                Socket socket = serverSocket.accept();

                UserConnectionHandler connectionHandler = new UserConnectionHandler(socket);
                connectionHandler.connectNewUser(new UserConnection(socket));
                ScheduledFuture<?> scheduledFuture =
                        executor.scheduleWithFixedDelay(connectionHandler,
                                INITIAL_POOL_DELAY_SECS_TIME,
                                POOL_DELAY_SECS_TIME, TimeUnit.SECONDS);
                scheduledActiveTasks.put(socket, scheduledFuture);
            } catch (ConnectException e) {
                graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                        "An error occurred when connecting a new user"));
            } catch (Exception e) {
                graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                        "Connection to the server is lost"));
                hasServerStarted = false;
                break;
            }
        }
    }

    protected void sendBroadcastMessage(Message message) {
        for (UserConnection userConnection : serverModel.getOnlineUsersConnections().values()) {
            try {
                userConnection.send(message);
            } catch (Exception e) {
                graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                        "Error sending a message to all users"));
            }
        }
    }

    private class UserConnectionHandler implements Runnable {
        private final Socket userSocket;
        private ChatUserRecord userRecord;

        public UserConnectionHandler(Socket userSocket) {
            this.userSocket = userSocket;
        }

        public void connectNewUser(UserConnection userConnection) throws ConnectException {
            while (true) {
                try {
                    Message responseForUsername = requestUsernameFromNewUser(userConnection);
                    Message responseForPassword = requestCurrentSessionPasswordFromNewUser(userConnection);

                    userRecord = new ChatUserRecord(userConnection, getUsernameFromResponseMessage(responseForUsername));

                    if (MessageType.isTypeNewUsername(responseForUsername.getMessageType())
                            && MessageType.isTypeNewPassword(responseForPassword.getMessageType())
                            && isUsernameAvailableToAdd(userRecord.getUsername())
                            && serverModel.isCurrentSessionPasswordCorrect(responseForPassword.getMessageText())) {
                        addNewUserToServerModel();
                        sendToNewUserAllOnlineUsernamesByConnection(userConnection);
                        sendBroadcastMessage(new Message(MessageType.NEW_USER_ADDED, userRecord.getUsername()));
                        graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                                "A new user connected with a remote socket " + userSocket.getRemoteSocketAddress().toString()));
                        break;
                    } else {
                        userConnection.send(new Message(MessageType.LOGIN_ERROR));
                    }
                } catch (Exception exception) {
                    cancelScheduledActiveTask();
                    removeUserFromServerModel();
                    throw new ConnectException();
                }
            }
        }

        private void addNewUserToServerModel() {
            serverModel.addNewUserConnection(userRecord.getUsername(), userRecord.getUserConnection());
            serverModel.addNewUserMetaInfo(userRecord.getUsername(),
                    UserMetaInfo.builder()
                            .firstConnectionTime(FormatMessagesBuilder.buildDateNow())
                            .username(userRecord.getUsername())
                            .allSentMessagesNumber(0)
                            .lastMessageTime(FormatMessagesBuilder.buildDateNow())
                            .build());
            notifyObservers(new Message(MessageType.NOTIFY_ADD, userRecord.getUsername()));
        }

        private Message requestUsernameFromNewUser(UserConnection userConnection) throws IOException {
            userConnection.send(new Message(MessageType.REQUEST_USERNAME));
            return userConnection.receive();
        }

        private Message requestCurrentSessionPasswordFromNewUser(UserConnection userConnection) throws IOException {
            userConnection.send(new Message(MessageType.REQUEST_PASSWORD));
            return userConnection.receive();
        }

        private String getUsernameFromResponseMessage(Message responseMessage) {
            return responseMessage.getMessageText();
        }

        private boolean isUsernameAvailableToAdd(String username) {
            return username != null && !username.trim().isEmpty() && !serverModel.getOnlineUsersConnections().containsKey(username);
        }

        private void sendToNewUserAllOnlineUsernamesByConnection(UserConnection userConnection) {
            Set<String> listUsers = new HashSet<>(serverModel.getOnlineUsersConnections().keySet());
            userConnection.send(new Message(MessageType.LOGIN_ACCEPTED, listUsers));
        }

        private void messagingBetweenUsers() {
            try {
                if (!userRecord.getUserConnection().areThereInSocketAnyData()) {
                    return;
                }

                Message messageFromUser = userRecord.getUserConnection().receive();

                if (MessageType.isTypeTextMessage(messageFromUser.getMessageType())) {
                    sendMessageFromUserToEveryone(messageFromUser);
                }

                if (MessageType.isTypeDisconnect(messageFromUser.getMessageType())) {
                    disableExistedUserFromChat();
                }
            } catch (Exception exception) {
                graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                        "An error occurred when sending a message from user " + userRecord.getUsername() + " with address " + userSocket.getRemoteSocketAddress()));
                cancelScheduledActiveTask();
                removeUserFromServerModel();
            }
        }


        private void sendMessageFromUserToEveryone(Message message) {
            if (message.getMessageText() != null && !message.getMessageText().trim().isEmpty()) {
                String textMessage = FormatMessagesBuilder.buildChatTextAreaUserMessage(userRecord.getUsername(), message.getMessageText());
                sendBroadcastMessage(new Message(MessageType.TEXT_MESSAGE, textMessage));
                serverModel.getUserMetaInfoByUsername(userRecord.getUsername()).updateLastMessageTime();
            }
        }

        private void disableExistedUserFromChat() throws IOException {
            sendBroadcastMessage(new Message(MessageType.USER_DELETED, userRecord.getUsername()));
            removeUserFromServerModel();
            userRecord.getUserConnection().close();
            cancelScheduledActiveTask();
            graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                    "The user with remote address " + userSocket.getRemoteSocketAddress() + " has disconnected"));
        }

        private void removeUserFromServerModel() {
            if (userRecord != null) {
                serverModel.removeUserConnectionByUsername(userRecord.getUsername());
                serverModel.removeUserMetaInfoByUsername(userRecord.getUsername());
                notifyObservers(new Message(MessageType.NOTIFY_REMOVE, userRecord.getUsername()));
            }
        }

        private void cancelScheduledActiveTask() {
            if (scheduledActiveTasks.containsKey(userSocket)) {
                scheduledActiveTasks.get(userSocket).cancel(true);
            }
            scheduledActiveTasks.remove(userSocket);
        }

        @Override
        public void run() {
            try {
                messagingBetweenUsers();
            } catch (Exception exception) {
                graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                        "An error occurred when sending a message from a user"));
            }
        }
    }

    private class SessionPasswordUpdater extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    Thread.sleep(PASSWORD_EXPIRATION_MILLIS_TIME);
                    generateNewSessionPassword();
                } catch (InterruptedException exception) {
                    graphicView.addServiceMessageToServerLogsTextArea(FormatMessagesBuilder.buildMessageWithDateNow(
                            "SessionPasswordUpdater was stopped by interrupt"));
                    break;
                }
            }
        }
    }
}