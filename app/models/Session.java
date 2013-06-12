/*
 * Copyright (C) 2013
 * johann.bornholdt@gmail.com
 * 
 * This file is part of Hermodr-Server.
 *
 * Hermodr-Server is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Hermodr-Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Hermodr-Server.  If not, see <http://www.gnu.org/licenses/>.
 * 
 */

package models;

import static akka.pattern.Patterns.ask;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.HashMap;
import java.util.Map;

import play.Logger;
import play.libs.Akka;
import play.libs.F.Callback;
import play.libs.F.Callback0;
import play.libs.Json;
import play.mvc.WebSocket;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class Session extends UntypedActor {
	
	private static final int gridSize = 20;
	
	private static Map<String, ActorRef> allSessions = new HashMap<String, ActorRef>();
	
	private Map<String, WebSocket.Out<JsonNode>> viewers = new HashMap<String, WebSocket.Out<JsonNode>>();
	
	private String[][] imageStrings = new String[gridSize][gridSize];
	
	private String presenterName;
	
	public static void createSession(final String presenterName, WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out) throws Exception {
		Logger.info("createSession presenterName: " + presenterName);
		final ActorRef session = Akka.system().actorOf(new Props(Session.class));
		String result = (String)Await.result(ask(session,new PresenterJoin(presenterName, session), 1000), Duration.create(1, SECONDS));
		
		if ("OK".equals(result)) {
			
			// For each event received on the socket,
			in.onMessage(new Callback<JsonNode>() {
				String imageString = "";
				String audioString = "";

				@SuppressWarnings("deprecation")
				@Override
				public void invoke(JsonNode event) throws Throwable {
					
					if (event.get("type").asText().equals("image")) {
						
						String part = event.get("data").asText();
						
						imageString = part + imageString;
						
						if ((event.get("last").asText()).equals("1")) {
							
							session.tell(new SendImage(imageString, event.get("x").asInt(), event.get("y").asInt()));
							
							imageString = "";
						}
					} else if (event.get("type").asText().equals("audio")) {
						String part = event.get("data").asText();

						audioString = part + audioString;

						if ((event.get("last").asText()).equals("1")) {

							session.tell(new SendAudio(audioString));

							audioString = "";
						}
					} else if (event.get("type").asText().equals("cursor")) {
						int x = event.get("x").asInt();
						int y = event.get("y").asInt();
						session.tell(new SendCursor(x, y));
					}


				}
			});

			// When the socket is closed.
			in.onClose(new Callback0() {
				@SuppressWarnings("deprecation")
				public void invoke() {

					// Send a Quit message to the room.
					session.tell(new PresenterQuit());
				}
			});

		} else {

			// Cannot connect, create a Json error.
			ObjectNode error = Json.newObject();
			error.put("kind", "error");
			error.put("presenter", presenterName);
			error.put("data", result);

			// Send the error to the socket.
			out.write(error);

		}
	}
	
	public static void joinSession(final String presenterName, final String username, WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out) throws Exception {
		Logger.info("joinSession presenterName: " + presenterName + " username: " + username);
		
		String result = null;
		final ActorRef session = allSessions.get(presenterName);
		
		if (allSessions.containsKey(presenterName)) {
			result = (String)Await.result(ask(session,new Join(username, out), 1000), Duration.create(1, SECONDS));
		} else {
			result = "The Session you requested doesn't exist.";
		}
		
		if ("OK".equals(result)) {
			
			// For each event received on the socket,
//			in.onMessage(new Callback<JsonNode>() {
//				public void invoke(JsonNode event) {
//
//					// Send a Talk message to the room.
//					session.tell(new Talk(presenterName, event.get("text").asText()));
//
//				}
//			});

			// When the socket is closed.
			in.onClose(new Callback0() {
				@SuppressWarnings("deprecation")
				public void invoke() {
					// Send a Quit message to the room.
					session.tell(new Quit(username));

				}
			});

		} else {

			// Cannot connect, create a Json error.
			ObjectNode error = Json.newObject();
			error.put("kind", "error");
			error.put("presenter", presenterName);
			error.put("data", result);
			out.write(error);

		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void onReceive(Object message) throws Exception {

		if (message instanceof Join) {

			// Received a Join message
			Join join = (Join) message;

			// Check if this username is free.
			if (viewers.containsKey(join.username)) {
				Logger.info("onReceive: Join: This username is already used");
				getSender().tell("This username is already used");
			} else {
				Logger.info("onReceive: Join: " + join.username + "has entered the room: " + this.presenterName);
				viewers.put(join.username, join.viewer);
				Logger.info(presenterName + ".size: " + viewers.size());
				sendCompleteImage(join.viewer);
				getSender().tell("OK");
			}

		} else if (message instanceof PresenterJoin) {
			
			// Received a Talk message
			PresenterJoin presenterJoin = (PresenterJoin) message;
			if (allSessions.containsKey(presenterJoin.presenterName)) {
				Logger.info("onReceive: PresenterJoin: There is still a Session from your account running.");
				getSender().tell("There is still a Session from your account running.");
			} else {
				Logger.info("onReceive: PresenterJoin: Session created");
				this.presenterName = presenterJoin.presenterName;
				allSessions.put(presenterJoin.presenterName, presenterJoin.session);
				getSender().tell("OK");
			}
			Logger.info("allSessions.size: " + allSessions.size());
			
		} else if (message instanceof SendImage) {
//			Logger.info("onReceive: SendImage");

			// Received a Talk message
			SendImage sendImage = (SendImage) message;

			updateImage(sendImage.text, sendImage.x, sendImage.y);
		} else if (message instanceof SendAudio) {
//			Logger.info("onReceive: SendAudio");
			
			// Received a Talk message
			SendAudio sendAudio = (SendAudio) message;
			
			updateAudio(sendAudio.text);
		} else if (message instanceof SendCursor) {
//			Logger.info("onReceive: SendCursor");
			
			// Received a Talk message
			SendCursor sendCursor = (SendCursor) message;
			
			updateCursor(sendCursor.x, sendCursor.y);

		} else if (message instanceof Quit) {
			Logger.info("onReceive: Quit");

			// Received a Quit message
			Quit quit = (Quit) message;

			viewers.remove(quit.username);
			
			Logger.info(presenterName + ".size: " + viewers.size());

		} else if (message instanceof PresenterQuit) {
			Logger.info("onReceive: PresenterQuit");
			
			notifyAll("error", "The Screencast has been ended by the Presenter");
			
			for (WebSocket.Out<JsonNode> viewer : viewers.values()) {
				viewer.close();
			}
			
			allSessions.remove(presenterName);
			Logger.info("allSessions.size: " + allSessions.size());

		} else {
			Logger.info("onReceive: unhandled: " + message.toString());
			unhandled(message);
		}

	}

	private void notifyAll(String kind, String text) {
		//TODO
//		Logger.info("notifyAll kind: " + kind + " user: " + presenter);
		for (WebSocket.Out<JsonNode> viewer : viewers.values()) {
			ObjectNode event = Json.newObject();
			event.put("kind", kind);
            event.put("data", text);
            
            viewer.write(event);
					
		}
	}
	
	private void sendCompleteImage(WebSocket.Out<JsonNode> viewer) {
		
		for (int x = 0; x < imageStrings.length; x++) {
			for (int y = 0; y < imageStrings[x].length; y++) {
				if (imageStrings[x][y] != null) {
					ObjectNode event = Json.newObject();
					event.put("kind", "sendImage");
					event.put("x", x);
					event.put("y", y);
					event.put("data", imageStrings[x][y]);
					viewer.write(event);
				}
			}
		}
		
	}
	
	private void updateImage(String text, int x, int y) {
		imageStrings[x][y] = text;
		for (WebSocket.Out<JsonNode> viewer : viewers.values()) {
			ObjectNode event = Json.newObject();
			event.put("kind", "sendImage");
			event.put("x", x);
			event.put("y", y);
			event.put("data", text);
			viewer.write(event);
		}
	}
	
	private void updateAudio(String text) {
		for (WebSocket.Out<JsonNode> viewer : viewers.values()) {
			ObjectNode event = Json.newObject();
			event.put("kind", "sendAudio");
			event.put("data", text);
			viewer.write(event);
		}
	}
	
	private void updateCursor(int x, int y) {
//		Logger.info("updateCursor kind: " + kind + " user: " + presenter + " " + x + " " + y);
		for (WebSocket.Out<JsonNode> viewer : viewers.values()) {
			ObjectNode event = Json.newObject();
			event.put("kind", "sendCursor");
            event.put("x", x);
            event.put("y", y);
            
            viewer.write(event);
			
		}
	}
	
	// -- Messages

	public static class Join {

		final String username;
		final WebSocket.Out<JsonNode> viewer;

		public Join(String username, WebSocket.Out<JsonNode> session) {
			this.username = username;
			this.viewer = session;
		}

	}
	
	public static class PresenterJoin {
		
		final String presenterName;
		final ActorRef session;
		
		public PresenterJoin(String presenterName, ActorRef session) {
			this.presenterName = presenterName;
			this.session = session;
		}
		
	}

	public static class SendImage {

		final String text;
		final int x;
		final int y;

		public SendImage(String text, int x, int y) {
			this.text = text;
			this.x = x;
			this.y = y;
		}

	}
	
	public static class SendAudio {
		
		final String text;
		
		public SendAudio(String text) {
			this.text = text;
		}
		
	}
	
	public static class SendCursor {
		
		final int x;
		final int y;
		
		public SendCursor(int x, int y) {
			this.x = x;
			this.y = y;
		}
		
	}

	public static class Quit {

		final String username;

		public Quit(String username) {
			this.username = username;
		}

	}

	public static class PresenterQuit {
	}
}
