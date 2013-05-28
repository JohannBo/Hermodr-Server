package models;

import static akka.pattern.Patterns.ask;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.FileOutputStream;
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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ning.http.util.Base64;


public class Session extends UntypedActor {
	
	private static Map<String, ActorRef> allSessions = new HashMap<String, ActorRef>();
	
	private Map<String, WebSocket.Out<JsonNode>> viewers = new HashMap<String, WebSocket.Out<JsonNode>>();
	
	private String presenterName;
	
	public static void createSession(final String presenterName, WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out) throws Exception {
		Logger.info("createSession presenterName: " + presenterName);
		final ActorRef session = Akka.system().actorOf(new Props(Session.class));
		String result = (String)Await.result(ask(session,new PresenterJoin(presenterName, session), 1000), Duration.create(1, SECONDS));
		
		if ("OK".equals(result)) {
			
			// For each event received on the socket,
			in.onMessage(new Callback<JsonNode>() {
				String base64string = "";

				@Override
				public void invoke(JsonNode event) throws Throwable {
					
					if (event.get("type").asText().equals("image")) {
						
						String part = event.get("data").asText();
						
						base64string = part + base64string;
						
						if ((event.get("last").asText()).equals("1")) {
							byte[] imageInBytes = Base64.decode(base64string);
							
//							FileOutputStream fos = new FileOutputStream("foobar.jpg");
//							fos.write(imageInBytes);
//							fos.close();
//							Logger.info("done saving image");
							session.tell(new SendImage(presenterName, base64string));
//							Logger.info("length: " + base64string.length());
							
							base64string = "";
						}
					} else if (event.get("type").asText().equals("cursor")) {
						int x = event.get("x").asInt();
						int y = event.get("y").asInt();
						session.tell(new SendCursor(presenterName, x, y));
					}


				}
			});

			// When the socket is closed.
			in.onClose(new Callback0() {
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
	
	public static void joinSession(final String presenterName, String username, WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out) throws Exception {
		Logger.info("joinSession presenterName: " + presenterName + " username: " + username);
		
		String result;
		
		if (allSessions.containsKey(presenterName)) {
			ActorRef session = allSessions.get(presenterName);
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
				public void invoke() {

					// Send a Quit message to the room.
//					session.tell(new Quit(presenterName));

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
				viewers.put(join.username, join.session);
				Logger.info(presenterName + ".size: " + viewers.size());
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

			notifyAll("sendImage", sendImage.username, sendImage.text);
		} else if (message instanceof SendCursor) {
//			Logger.info("onReceive: SendCursor");
			
			// Received a Talk message
			SendCursor sendCursor = (SendCursor) message;
			
			updateCursor("sendCursor", sendCursor.username, sendCursor.x, sendCursor.y);

		} else if (message instanceof Quit) {
			Logger.info("onReceive: Quit");

			// Received a Quit message
			Quit quit = (Quit) message;

			viewers.remove(quit.username);
			
			Logger.info(presenterName + ".size: " + viewers.size());

		} else if (message instanceof PresenterQuit) {
			Logger.info("onReceive: PresenterQuit");
			
			// Received a Quit message
			PresenterQuit presenterQuit = (PresenterQuit) message;
			
			notifyAll("error", "system", "The Screencast has been ended by the Presenter");
			
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

	private void notifyAll(String kind, String presenter, String text) {
		//TODO
//		Logger.info("notifyAll kind: " + kind + " user: " + presenter);
		for (WebSocket.Out<JsonNode> viewer : viewers.values()) {
			ObjectNode event = Json.newObject();
			event.put("kind", kind);
            event.put("presenter", presenter);
            event.put("data", text);
            
            viewer.write(event);
			
		}
		
	}
	
	private void updateCursor(String kind, String presenter, int x, int y) {
//		Logger.info("updateCursor kind: " + kind + " user: " + presenter + " " + x + " " + y);
		for (WebSocket.Out<JsonNode> viewer : viewers.values()) {
			ObjectNode event = Json.newObject();
			event.put("kind", kind);
            event.put("presenter", presenter);
            event.put("x", x);
            event.put("y", y);
            
            viewer.write(event);
			
		}
	}

	// -- Messages

	public static class Join {

		final String username;
		final WebSocket.Out<JsonNode> session;

		public Join(String username, WebSocket.Out<JsonNode> session) {
			this.username = username;
			this.session = session;
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

		final String username;
		final String text;

		public SendImage(String username, String text) {
			this.username = username;
			this.text = text;
		}

	}
	
	public static class SendCursor {
		
		final String username;
		final int x;
		final int y;
		
		public SendCursor(String username, int x, int y) {
			this.username = username;
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
