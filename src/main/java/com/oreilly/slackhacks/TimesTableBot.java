package com.oreilly.slackhacks;

import com.ullink.slack.simpleslackapi.SlackSession;
import com.ullink.slack.simpleslackapi.SlackUser;
import com.ullink.slack.simpleslackapi.events.SlackMessagePosted;
import com.ullink.slack.simpleslackapi.impl.SlackSessionFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class TimesTableBot {

    private static final String TOKEN = "insert your token here";

    private static final int TOTAL_TEST_SIZE = 10;
    private static final int TEST_MAX_TIME = 60000;

    private static class GameSession {
        SlackUser user;
        int questionCounter = 0;
        int goodResult = 0;
        long cumulativeTime = 0;
        String questionTimestamp;
        Thread timer;
    }

    private static Map<String, GameSession> gameSessions = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        //creating the session
        SlackSession session = SlackSessionFactory.createWebSocketSlackSession(TOKEN);
        //adding a message listener to the session
        session.addMessagePostedListener(TimesTableBot::processMessagePostedEvent);
        //connecting the session to the Slack team
        session.connect();
        //delegating all the event management to the session
        Thread.sleep(Long.MAX_VALUE);
    }

    private static void processMessagePostedEvent(SlackMessagePosted event, SlackSession session) {
        handleTestTrigger(event, session);
        handleAnswer(event, session);
    }

    private static void handleTestTrigger(SlackMessagePosted event, SlackSession session) {
        //trigger is accepted only on direct message to the bot
        if (!event.getChannel().isDirect()) {
            return;
        }
        //looking for !times command
        if ("!times".equals(event.getMessageContent().trim())) {
            // check if a game is already running with this user, ignoring the command in this case
            if (gameSessions.containsKey(event.getSender().getId())) {
                return;
            }
            GameSession gameSession = prepareGameSession(event, session);
            gameSession.timer.start();
            sendTimes(gameSession, session);
        }
    }

    private static GameSession prepareGameSession(SlackMessagePosted event, SlackSession session) {
        GameSession gameSession = new GameSession();
        gameSession.user = event.getSender();
        gameSessions.put(event.getSender().getId(), gameSession);
        gameSession.timer = buildTimer(session, gameSession);
        return gameSession;

    }

    private static void sendTimes(GameSession gameSession, SlackSession session) {
        //select two values to multiply
        int a = 1 + (int) (Math.random() * 10);
        int b = 1 + (int) (Math.random() * 10);
        gameSession.goodResult = a * b;
        gameSession.questionTimestamp = session.sendMessageToUser(gameSession.user, a + " x " + b, null).getReply().getTimestamp();
    }

    private static Thread buildTimer(final SlackSession session, final GameSession gameSession) {
        return new Thread() {
            @Override
            public void run() {
                try {
                    Thread.sleep(TEST_MAX_TIME);
                    if (gameSessions.containsKey(gameSession.user.getId())) {
                        if (gameSession.questionCounter < TOTAL_TEST_SIZE) {
                            session.sendMessageToUser(gameSession.user, "Time's up, try again", null);
                            gameSessions.remove(gameSession.user.getId());
                        }
                    }
                } catch (InterruptedException e) {
                    //interrupted by the good answer
                }
            }
        };
    }

    private static void handleAnswer(SlackMessagePosted event, SlackSession session) {
        //no test launched for this user
        GameSession gameSession = gameSessions.get(event.getSender().getId());
        if (gameSession == null) {
            return;
        }
        //an answer should be given on a direct channel
        if (!event.getChannel().isDirect()) {
            return;
        }
        //an answer is a number
        String answerValue = event.getMessageContent().trim();
        try {
            int resultGiven = Integer.parseInt(answerValue);
            if (resultGiven == gameSession.goodResult) {
                //good answer
                goodAnswer(event, session, gameSession);
            } else {
                wrongAnswer(event, session);
            }
        } catch (NumberFormatException e) {
            //ignore the result
            return;
        }
    }

    private static void wrongAnswer(SlackMessagePosted event, SlackSession session) {
        session.addReactionToMessage(event.getChannel(), event.getTimeStamp(), "x");
    }

    private static void goodAnswer(SlackMessagePosted event, SlackSession session, GameSession gameSession) {
        session.addReactionToMessage(event.getChannel(), event.getTimeStamp(), "white_check_mark");
        computeTime(event, gameSession);
        nextTestStep(session, gameSession);
    }

    private static void computeTime(SlackMessagePosted event, GameSession gameSession) {
        long start = toEpochTimeStamp(gameSession.questionTimestamp);
        long end = toEpochTimeStamp(event.getTimestamp());
        gameSession.cumulativeTime += (end - start);
    }

    private static long toEpochTimeStamp(String slackTimeStamp) {
        return Long.parseLong(slackTimeStamp.substring(0, slackTimeStamp.indexOf('.')));
    }

    private static void nextTestStep(SlackSession session, GameSession gameSession) {
        gameSession.questionCounter++;
        if (gameSession.questionCounter < TOTAL_TEST_SIZE) {
            sendTimes(gameSession, session);
        } else {
            showTestResults(gameSession, session);
        }
    }

    private static void showTestResults(GameSession gameSession, SlackSession session) {
        session.sendMessageToUser(gameSession.user, "You took " + gameSession.cumulativeTime + " seconds to complete the test", null);
        gameSessions.remove(gameSession.user.getId());
    }


}
