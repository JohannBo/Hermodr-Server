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

package controllers;

import models.Session;
import play.Logger;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.WebSocket;
import views.html.*;

import com.fasterxml.jackson.databind.JsonNode;

public class Application extends Controller {
	


    public static Result index() {
    	Logger.info("Application.index()");
        return ok(index.render());
    }
    
    public static Result whiteboard(String presenter, String username) {
    	Logger.info("Application.whiteboard()");
    	
    	return ok(whiteboard.render(presenter, username));
    }
    
    public static WebSocket<JsonNode> presenter(final String presenterName) {
    	Logger.info("Application.presenter()");
        return new WebSocket<JsonNode>() {
            
            // Called when the Websocket Handshake is done.
            public void onReady(WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out){
                
                // Join the chat room.
                try { 
                    Session.createSession(presenterName, in, out);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        };
    }
    
    public static WebSocket<JsonNode> viewer(final String presenterName, final String username) {
    	Logger.info("Application.viewer()");
    	return new WebSocket<JsonNode>() {
    		
    		// Called when the Websocket Handshake is done.
    		public void onReady(WebSocket.In<JsonNode> in, WebSocket.Out<JsonNode> out){
    			
    			// Join the chat room.
    			try { 
    				Session.joinSession(presenterName, username, in, out);
    			} catch (Exception ex) {
    				ex.printStackTrace();
    			}
    		}
    	};
    }

}
