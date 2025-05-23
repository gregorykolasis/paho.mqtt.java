/*******************************************************************************
 * Copyright (c) 2009, 2019 IBM Corp.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution. 
 *
 * The Eclipse Public License is available at 
 *    https://www.eclipse.org/legal/epl-2.0
 * and the Eclipse Distribution License is available at 
 *   https://www.eclipse.org/org/documents/edl-v10.php
 *
 * Contributors:
 *    Dave Locke - initial API and implementation and/or initial documentation
 *    Ian Craggs - per subscription message handlers (bug 466579)
 *    Ian Craggs - ack control (bug 472172)
 *    James Sutton - Automatic Reconnect & Offline Buffering    
 */
package org.eclipse.paho.client.mqttv3.internal;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttToken;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttPubAck;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttPubComp;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttPublish;
import org.eclipse.paho.client.mqttv3.internal.wire.MqttWireMessage;
import org.eclipse.paho.client.mqttv3.logging.Logger;
import org.eclipse.paho.client.mqttv3.logging.LoggerFactory;

import static org.eclipse.paho.client.mqttv3.internal.CommsSender.MAX_STOPPED_STATE_TO_STOP_THREAD;

/**
 * Bridge between Receiver and the external API. This class gets called by
 * Receiver, and then converts the comms-centric MQTT message objects into ones
 * understood by the external API.
 */
public class CommsCallback implements Runnable {
	private static final String CLASS_NAME = CommsCallback.class.getName();
	private final Logger log = LoggerFactory.getLogger(LoggerFactory.MQTT_CLIENT_MSG_CAT, CLASS_NAME);

	private static final int INBOUND_QUEUE_SIZE = 512;
	private MqttCallback mqttCallback;
	private MqttCallbackExtended reconnectInternalCallback;
	private final Hashtable<String, IMqttMessageListener> callbacksWildcards; // topicFilter with wildcards -> messageHandler
	private final Hashtable<String, IMqttMessageListener> callbacksDirect; // topicFilter without wildcards -> messageHandler
	private final ClientComms clientComms;
	private final Vector<MqttWireMessage> messageQueue;
	private final Vector<MqttToken> completeQueue;
	
	private enum State {STOPPED, RUNNING, QUIESCING}

	private State current_state = State.STOPPED;
	private State target_state = State.STOPPED;
	private final Object lifecycle = new Object();
	private Thread callbackThread;
	private String threadName;
	private Future<?> callbackFuture;
	
	private final Object workAvailable = new Object();
	private final Object spaceAvailable = new Object();
	private ClientState clientState;
	private boolean manualAcks = false;

	CommsCallback(ClientComms clientComms) {
		this.clientComms = clientComms;
		this.messageQueue = new Vector<MqttWireMessage>(INBOUND_QUEUE_SIZE);
		this.completeQueue = new Vector<MqttToken>(INBOUND_QUEUE_SIZE);
		this.callbacksWildcards = new Hashtable<String, IMqttMessageListener>();
		this.callbacksDirect = new Hashtable<String, IMqttMessageListener>();
		log.setResourceName(clientComms.getClient().getClientId());
	}

	public void setClientState(ClientState clientState) {
		this.clientState = clientState;
	}

	/**
	 * Starts up the Callback thread.
	 * @param threadName The name of the thread
	 * @param executorService the {@link ExecutorService}
	 */
	public void start(String threadName, ExecutorService executorService) {
		this.threadName = threadName;

		synchronized (lifecycle) {
			if (current_state == State.STOPPED) {
				// Preparatory work before starting the background thread.
				// For safety ensure any old events are cleared.
				messageQueue.clear();
				completeQueue.clear();
				
				target_state = State.RUNNING;
				current_state = State.RUNNING;
				if (executorService == null) {
					callbackFuture = null;
					callbackThread = new Thread(this);
					callbackThread.start();
				} else {
					callbackThread = null;
					callbackFuture = executorService.submit(this);
				}
			}
		}

		AtomicInteger stoppedStateCounter = new AtomicInteger(0);
		while (!isRunning()) {
			try { Thread.sleep(100); } catch (Exception e) { }
			if (current_state == State.STOPPED) {
				if (stoppedStateCounter.incrementAndGet() > MAX_STOPPED_STATE_TO_STOP_THREAD) {
					break;
				}
			} else {
				stoppedStateCounter.set(0);
			}
		}			
	}

	/**
	 * Stops the callback thread. 
	 * This call will block until stop has completed.
	 */
	public void stop() {
		final String methodName = "stop";

		if (isRunning()) {
			// @TRACE 700=stopping
			log.fine(CLASS_NAME, methodName, "700");
			synchronized (lifecycle) {
				target_state = State.STOPPED;
			}
			// Do not allow a thread to wait for itself.
			if (!Thread.currentThread().equals(callbackThread)) {
				synchronized (workAvailable) {
					// @TRACE 701=notify workAvailable and wait for run
					// to finish
					log.fine(CLASS_NAME, methodName, "701");
					workAvailable.notifyAll();
				}
				// Wait for the thread to finish.
				if (callbackFuture != null) {
					try {
						callbackFuture.get();
					} catch (ExecutionException | InterruptedException e) {
					}
				} else {
					try {
						callbackThread.join();
					} catch (InterruptedException e) {
					}
				}
			}
			// @TRACE 703=stopped
			log.fine(CLASS_NAME, methodName, "703");
		}
	}

	public void setCallback(MqttCallback mqttCallback) {
		this.mqttCallback = mqttCallback;
	}
	
	public void setReconnectCallback(MqttCallbackExtended callback){
		this.reconnectInternalCallback = callback;
	}
	
	public void setManualAcks(boolean manualAcks) {
		this.manualAcks = manualAcks;
	}

	public void run() {
		final String methodName = "run";
		callbackThread = Thread.currentThread();
		callbackThread.setName(threadName);

		while (isRunning()) {
			try {
				// If no work is currently available, then wait until there is some...
				try {
					synchronized (workAvailable) {
						if (isRunning() && messageQueue.isEmpty()
								&& completeQueue.isEmpty()) {
							// @TRACE 704=wait for workAvailable
							log.fine(CLASS_NAME, methodName, "704");
							workAvailable.wait();
						}
					}
				} catch (InterruptedException e) {
				}

				if (isRunning()) {
					// Check for deliveryComplete callbacks...
					MqttToken token = null;
					synchronized (completeQueue) {
					    if (!completeQueue.isEmpty()) {
						    // First call the delivery arrived callback if needed
						    token = (MqttToken) completeQueue.elementAt(0);
						    completeQueue.removeElementAt(0);
					    }
					}
					if (null != token) {
						handleActionComplete(token);
					}
					
					// Check for messageArrived callbacks...
					MqttPublish message = null;
					synchronized (messageQueue) {
					    if (!messageQueue.isEmpty()) {
						    // Note, there is a window on connect where a publish
						    // could arrive before we've
						    // finished the connect logic.
							message = (MqttPublish) messageQueue.elementAt(0);

							messageQueue.removeElementAt(0);
					    }
					}
					if (null != message) {
						handleMessage(message);
					}
				}

				if (isQuiescing()) {
					clientState.checkQuiesceLock();
				}
				
			} catch (Throwable ex) {
				// Users code could throw an Error or Exception e.g. in the case
				// of class NoClassDefFoundError
				// @TRACE 714=callback threw exception
				log.fine(CLASS_NAME, methodName, "714", null, ex);

				clientComms.shutdownConnection(null, new MqttException(ex));
			} finally {

			    synchronized (spaceAvailable) {
                    // Notify the spaceAvailable lock, to say that there's now
                    // some space on the queue...

                    // @TRACE 706=notify spaceAvailable
                    log.fine(CLASS_NAME, methodName, "706");
                    spaceAvailable.notifyAll();
                }
			}
		}
		synchronized (lifecycle) {
			current_state = State.STOPPED;
		}
	}

	private void handleActionComplete(MqttToken token)
			throws MqttException {
		final String methodName = "handleActionComplete";
		synchronized (token) {
			// @TRACE 705=callback and notify for key={0}
			log.fine(CLASS_NAME, methodName, "705",	new Object[] { token.internalTok.getKey() });
			if (token.isComplete()) {
				// Finish by doing any post processing such as delete 
				// from persistent store but only do so if the action
				// is complete
				clientState.notifyComplete(token);
			}
			
			// Unblock any waiters and if pending complete now set completed
			token.internalTok.notifyComplete();
			
 			if (!token.internalTok.isNotified()) {
 				// If a callback is registered and delivery has finished 
 				// call delivery complete callback. 
				if ( mqttCallback != null 
					&& token instanceof MqttDeliveryToken 
					&& token.isComplete()) {
						mqttCallback.deliveryComplete((MqttDeliveryToken) token);
				}
				// Now call async action completion callbacks
				fireActionEvent(token);
			}
			
			// Set notified so we don't tell the user again about this action.
 			if ( token.isComplete() ){
 			   if ( token instanceof MqttDeliveryToken) {
 	                token.internalTok.setNotified(true);
 	            }
 			}
			

			
		}
	}

	/**
	 * This method is called when the connection to the server is lost. If there
	 * is no cause then it was a clean disconnect. The connectionLost callback
	 * will be invoked if registered and run on the thread that requested
	 * shutdown e.g. receiver or sender thread. If the request was a user
	 * initiated disconnect then the disconnect token will be notified.
	 * 
	 * @param cause  the reason behind the loss of connection.
	 */
	public void connectionLost(MqttException cause) {
		final String methodName = "connectionLost";
		// If there was a problem and a client callback has been set inform
		// the connection lost listener of the problem.
		try {
			if (mqttCallback != null && cause != null) {
				// @TRACE 708=call connectionLost
				log.fine(CLASS_NAME, methodName, "708", new Object[] { cause });
				mqttCallback.connectionLost(cause);
			}
			if(reconnectInternalCallback != null && cause != null){
				reconnectInternalCallback.connectionLost(cause);
			}
		} catch (java.lang.Throwable t) {
			// Just log the fact that a throwable has caught connection lost 
			// is called during shutdown processing so no need to do anything else
			// @TRACE 720=exception from connectionLost {0}
			log.fine(CLASS_NAME, methodName, "720", new Object[] { t });
		}
	}

	/**
	 * An action has completed - if a completion listener has been set on the
	 * token then invoke it with the outcome of the action.
	 * 
	 * @param token The {@link MqttToken} that has completed
	 */
	public void fireActionEvent(MqttToken token) {
		final String methodName = "fireActionEvent";

		if (token != null) {
			IMqttActionListener asyncCB = token.getActionCallback();
			if (asyncCB != null) {
				if (token.getException() == null) {
					// @TRACE 716=call onSuccess key={0}
					log.fine(CLASS_NAME, methodName, "716",
							new Object[] { token.internalTok.getKey() });
					asyncCB.onSuccess(token);
				} else {
					// @TRACE 717=call onFailure key {0}
					log.fine(CLASS_NAME, methodName, "716",
							new Object[] { token.internalTok.getKey() });
					asyncCB.onFailure(token, token.getException());
				}
			}
		}
	}

	/**
	 * This method is called when a message arrives on a topic. Messages are
	 * only added to the queue for inbound messages if the client is not
	 * quiescing.
	 * 
	 * @param sendMessage
	 *            the MQTT SEND message.
	 */
	public void messageArrived(MqttPublish sendMessage) {
		final String methodName = "messageArrived";
		if (mqttCallback != null || !callbacksWildcards.isEmpty() || !callbacksDirect.isEmpty()) {
			// If we already have enough messages queued up in memory, wait
			// until some more queue space becomes available. This helps 
			// the client protect itself from getting flooded by messages 
			// from the server.
			synchronized (spaceAvailable) {
				while (isRunning() && !isQuiescing() && messageQueue.size() >= INBOUND_QUEUE_SIZE) {
					try {
						// @TRACE 709=wait for spaceAvailable
						log.fine(CLASS_NAME, methodName, "709");
						spaceAvailable.wait(200);
					} catch (InterruptedException ex) {
					}
				}
			}
			if (!isQuiescing()) {
				messageQueue.addElement(sendMessage);
				// Notify the CommsCallback thread that there's work to do...
				synchronized (workAvailable) {
					// @TRACE 710=new msg avail, notify workAvailable
					log.fine(CLASS_NAME, methodName, "710");
					workAvailable.notifyAll();
				}
			}
		}
	}

	/**
	 * Let the call back thread quiesce. Prevent new inbound messages being
	 * added to the process queue and let existing work quiesce. (until the
	 * thread is told to shutdown).
	 */
	public void quiesce() {
		final String methodName = "quiesce";
		synchronized (lifecycle) {
			if (current_state == State.RUNNING)
			current_state = State.QUIESCING;
		}
		synchronized (spaceAvailable) {
			// @TRACE 711=quiesce notify spaceAvailable
			log.fine(CLASS_NAME, methodName, "711");
			// Unblock anything waiting for space...
			spaceAvailable.notifyAll();
		}
	}

	public boolean isQuiesced() {
		if (isQuiescing() && completeQueue.size() == 0 && messageQueue.size() == 0) {
			return true;
		}
		return false;
	}

	private void handleMessage(MqttPublish publishMessage)
			throws MqttException, Exception {
		final String methodName = "handleMessage";
		// If quisecing process any pending messages.

		String destName = publishMessage.getTopicName();

		// @TRACE 713=call messageArrived key={0} topic={1}
		log.fine(CLASS_NAME, methodName, "713", new Object[] {
				Integer.valueOf(publishMessage.getMessageId()), destName });
		deliverMessage(destName, publishMessage.getMessageId(),
				publishMessage.getMessage());

		if (!this.manualAcks) {
			if (publishMessage.getMessage().getQos() == 1) {
				this.clientComms.internalSend(new MqttPubAck(publishMessage),
						new MqttToken(clientComms.getClient().getClientId()));
			} else if (publishMessage.getMessage().getQos() == 2) {
				this.clientComms.deliveryComplete(publishMessage);
				MqttPubComp pubComp = new MqttPubComp(publishMessage);
				this.clientComms.internalSend(pubComp, new MqttToken(
						clientComms.getClient().getClientId()));
			}
		}
	}
	
	public void messageArrivedComplete(int messageId, int qos) 
		throws MqttException {
		if (qos == 1) {
			this.clientComms.internalSend(new MqttPubAck(messageId),
					new MqttToken(clientComms.getClient().getClientId()));
		} else if (qos == 2) {
			this.clientComms.deliveryComplete(messageId);
			MqttPubComp pubComp = new MqttPubComp(messageId);
			this.clientComms.internalSend(pubComp, new MqttToken(
					clientComms.getClient().getClientId()));
		}
	}

	public void asyncOperationComplete(MqttToken token) {
		final String methodName = "asyncOperationComplete";

		if (isRunning()) {
			// invoke callbacks on callback thread
			completeQueue.addElement(token);
			synchronized (workAvailable) {
				// @TRACE 715=new workAvailable. key={0}
				log.fine(CLASS_NAME, methodName, "715", new Object[] { token.internalTok.getKey() });
				workAvailable.notifyAll();
			}
		} else {
			// invoke async callback on invokers thread
			try {
				handleActionComplete(token);
			} catch (Throwable ex) {
				// Users code could throw an Error or Exception e.g. in the case
				// of class NoClassDefFoundError
				// @TRACE 719=callback threw ex:
				log.fine(CLASS_NAME, methodName, "719", null, ex);
				
				// Shutdown likely already in progress but no harm to confirm
				clientComms.shutdownConnection(null, new MqttException(ex));
			}

		}
	}

	/**
	 * Returns the thread used by this callback.
	 * @return The {@link Thread}
	 */
	protected Thread getThread() {
		return callbackThread;
	}


	public void setMessageListener(String topicFilter, IMqttMessageListener messageListener) {
		if (topicFilter.contains("#") || topicFilter.contains("+")) {
			this.callbacksWildcards.put(topicFilter, messageListener);
		} else {
			this.callbacksDirect.put(topicFilter, messageListener);
		}
	}
	
	
	public void removeMessageListener(String topicFilter) {
		this.callbacksWildcards.remove(topicFilter); // no exception thrown if the filter was not present
		this.callbacksDirect.remove(topicFilter); // no exception thrown if the filter was not present
	}
	
	public void removeMessageListeners() {
		this.callbacksWildcards.clear();
		this.callbacksDirect.clear();
	}
	
	
	protected boolean deliverMessage(String topicName, int messageId, MqttMessage aMessage) throws Exception
	{		
		boolean delivered = false;
		
		IMqttMessageListener callback = this.callbacksDirect.get(topicName);
		if (callback != null) {
			aMessage.setId(messageId);
			callback.messageArrived(topicName, aMessage);
			delivered = true;
		}
		
		Enumeration<String> keys = callbacksWildcards.keys();
		while (keys.hasMoreElements()) {
			String topicFilter = (String)keys.nextElement();
			// callback may already have been removed in the meantime, so a null check is necessary
			callback = callbacksWildcards.get(topicFilter);
			if(callback == null) {
				continue;
			}
			if (MqttTopic.isMatched(topicFilter, topicName)) {
				aMessage.setId(messageId);
				callback.messageArrived(topicName, aMessage);
				delivered = true;
			}
		}
		
		/* if the message hasn't been delivered to a per subscription handler, give it to the default handler */
		if (mqttCallback != null && !delivered) {
			aMessage.setId(messageId);
			mqttCallback.messageArrived(topicName, aMessage);
			delivered = true;
		}
		
		return delivered;
	}
	
	public boolean isRunning() {
		boolean result;
		synchronized (lifecycle) {
			result = ((current_state == State.RUNNING || current_state == State.QUIESCING)
					&& target_state == State.RUNNING);
		}
		return result;
	}
	
	public boolean isQuiescing() {
		boolean result;
		synchronized (lifecycle) {
			result = (current_state == State.QUIESCING);
		}
		return result;
	}
}
