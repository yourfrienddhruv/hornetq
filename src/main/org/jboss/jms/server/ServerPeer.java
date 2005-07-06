/**
 * JBoss, the OpenSource J2EE WebOS
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jboss.jms.server;

import org.jboss.remoting.InvokerLocator;
import org.jboss.jms.delegate.ConnectionFactoryDelegate;
import org.jboss.jms.server.endpoint.ServerConnectionFactoryDelegate;
import org.jboss.jms.server.container.JMSAdvisor;
import org.jboss.jms.server.remoting.JMSServerInvocationHandler;
import org.jboss.jms.client.JBossConnectionFactory;
import org.jboss.jms.client.container.JMSInvocationHandler;
import org.jboss.jms.client.container.InvokerInterceptor;
import org.jboss.jms.util.JNDIUtil;
import org.jboss.aop.ClassAdvisor;
import org.jboss.aop.DomainDefinition;
import org.jboss.aop.AspectManager;
import org.jboss.aop.Dispatcher;
import org.jboss.aop.util.PayloadKey;
import org.jboss.aop.metadata.SimpleMetaData;
import org.jboss.aop.advice.AdviceStack;
import org.jboss.aop.advice.Interceptor;
import org.jboss.messaging.core.MessageStore;
import org.jboss.messaging.core.AcknowledgmentStore;
import org.jboss.messaging.core.util.MessageStoreImpl;
import org.jboss.messaging.core.util.InMemoryAcknowledgmentStore;
import org.jboss.logging.Logger;

import javax.jms.ConnectionFactory;
import javax.naming.InitialContext;
import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.transaction.TransactionManager;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;

import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import EDU.oswego.cs.dl.util.concurrent.PooledExecutor;

/**
 * A JMS server peer.
 *
 * @author <a href="mailto:ovidiu@jboss.org">Ovidiu Feodorov</a>
 * @version <tt>$Revision$</tt>
 */
public class ServerPeer
{
   // Constants -----------------------------------------------------

   private static final Logger log = Logger.getLogger(ServerPeer.class);

   private static final String CONNECTION_FACTORY_JNDI_NAME = "ConnectionFactory";
   private static final String XACONNECTION_FACTORY_JNDI_NAME = "XAConnectionFactory";
   private static ObjectName DESTINATION_MANAGER_OBJECT_NAME;

   static
   {
      try
      {
         DESTINATION_MANAGER_OBJECT_NAME =
         new ObjectName("jboss.messaging:service=DestinationManager");
      }
      catch(Exception e)
      {
         log.error(e);
      }
   }


   // Static --------------------------------------------------------

   // Attributes ----------------------------------------------------

   protected String serverPeerID;
   protected InvokerLocator locator;
   protected ObjectName connector;


   protected ClientManager clientManager;
   protected DestinationManagerImpl destinationManager;
   protected Map connFactoryDelegates;
   protected Hashtable jndiEnvironment;
   protected InitialContext initialContext;
   protected MBeanServer mbeanServer;

   protected boolean started;

   protected ClassAdvisor connFactoryAdvisor;
   protected ClassAdvisor connAdvisor;
   protected ClassAdvisor sessionAdvisor;
   protected ClassAdvisor producerAdvisor;
   protected ClassAdvisor consumerAdvisor;
	protected ClassAdvisor browserAdvisor;
   protected ClassAdvisor genericAdvisor;
   

   protected PooledExecutor threadPool;

   protected MessageStore messageStore;
   protected AcknowledgmentStore acknowledgmentStore;

   protected TransactionManager transactionManager;

   protected int connFactoryIDSequence;

   // Constructors --------------------------------------------------


   public ServerPeer(String serverPeerID) throws Exception
   {
      this.serverPeerID = serverPeerID;
      this.connFactoryDelegates = new HashMap();

      // the default value to use, unless the JMX attribute is modified
      connector = new ObjectName("jboss.remoting:service=Connector,transport=socket");
   }

   /**
    * @param jndiEnvironment - map containing JNDI properties. Useful for testing. Passing null
    *        means the server peer uses default JNDI properties.
    */
   public ServerPeer(String serverPeerID, Hashtable jndiEnvironment) throws Exception
   {
      this(serverPeerID);
      this.jndiEnvironment = jndiEnvironment;
      started = false;
   }

   // Public --------------------------------------------------------

   //
   // JMX operations
   //

   public synchronized void create()
   {
      log.debug(this + " created");
   }

   public synchronized void start() throws Exception
   {
      if (started)
      {
         return;
      }

      log.debug(this + " starting");

      initialContext = new InitialContext(jndiEnvironment);

      mbeanServer = findMBeanServer();
      transactionManager = findTransactionManager();

      clientManager = new ClientManager(this);
      destinationManager = new DestinationManagerImpl(this);
      messageStore = new MessageStoreImpl("MessageStore");
      acknowledgmentStore = new InMemoryAcknowledgmentStore("AcknowledgmentStore");
            
      threadPool = new PooledExecutor();

      initializeRemoting();
      initializeAdvisors();

      mbeanServer.registerMBean(destinationManager, DESTINATION_MANAGER_OBJECT_NAME);

      setupConnectionFactories();
      
      started = true;

      log.info("JMS " + this + " started");
   }
   
  

   public synchronized void stop() throws Exception
   {
      if (!started)
      {
         return;
      }

      log.debug(this + " stopping");

      tearDownConnectionFactories();

      mbeanServer.unregisterMBean(DESTINATION_MANAGER_OBJECT_NAME);
      tearDownAdvisors();
      
      
      started = false;

      log.info("JMS " + this + " stopped");

   }

   public synchronized void destroy()
   {
      log.debug(this + " destroyed");
   }


   public void createQueue(String name) throws Exception
   {
      destinationManager.createQueue(name, null);
   }

   public void createTopic(String name) throws Exception
   {
      destinationManager.createTopic(name, null);
   }


   //
   // end of JMX operations
   //

   //
   // JMX attributes
   //

   public String getServerPeerID()
   {
      return serverPeerID;
   }

   public String getLocatorURI()
   {
      if (locator == null)
      {
         return null;
      }
      return locator.getLocatorURI();
   }


   public ObjectName getConnector()
   {
      return connector;
   }

   public void setConnector(ObjectName on)
   {
      connector = on;
   }


   //
   // end of JMX attributes
   //

   public synchronized boolean isStarted()
   {
      return started;
   }

   public InvokerLocator getLocator()
   {
      return locator;
   }

   public ClientManager getClientManager()
   {
      return clientManager;
   }

   public DestinationManagerImpl getDestinationManager()
   {
      return destinationManager;
   }

   public ConnectionFactoryDelegate getConnectionFactoryDelegate(String connectionFactoryID)
   {
      return (ConnectionFactoryDelegate)connFactoryDelegates.get(connectionFactoryID);
   }



   public ClassAdvisor getConnectionFactoryAdvisor()
   {
      return connFactoryAdvisor;
   }

   public ClassAdvisor getConnectionAdvisor()
   {
      return connAdvisor;
   }

   public ClassAdvisor getSessionAdvisor()
   {
      return sessionAdvisor;
   }

   public ClassAdvisor getProducerAdvisor()
   {
      return producerAdvisor;
   }
   
   public ClassAdvisor getBrowserAdvisor()
   {
      return browserAdvisor;
   }

   public ClassAdvisor getConsumerAdvisor()
   {
      return consumerAdvisor;
   }



   public PooledExecutor getThreadPool()
   {
      return threadPool;
   }

   public MessageStore getMessageStore()
   {
      return messageStore;
   }

   public AcknowledgmentStore getAcknowledgmentStore()
   {
      return acknowledgmentStore;
   }

   public TransactionManager getTransactionManager()
   {
      return transactionManager;
   }

   public String toString()
   {
      StringBuffer sb = new StringBuffer();
      sb.append("ServerPeer (id=");
      sb.append(getServerPeerID());
      sb.append(")");
      return sb.toString();
   }

   // Package protected ---------------------------------------------

   Hashtable getJNDIEnvironment()
   {
      return jndiEnvironment;
   }

   // Protected -----------------------------------------------------
   
   // Private -------------------------------------------------------

   private static String[] domainNames = { "ServerConnectionFactoryDelegate",
                                           "ServerConnectionDelegate",
                                           "ServerSessionDelegate",
                                           "ServerProducerDelegate",
														 "ServerConsumerDelegate",
                                           "ServerBrowserDelegate",
                                           "GenericTarget"};

   private void initializeAdvisors() throws Exception
   {

      ClassAdvisor[] advisors = new ClassAdvisor[7];

      for(int i = 0; i < domainNames.length; i++)
      {
         DomainDefinition domainDefinition = AspectManager.instance().getContainer(domainNames[i]);
         if (domainDefinition == null)
         {
            throw new RuntimeException("Domain " + domainNames[i] + " not found");
         }
         advisors[i] = new JMSAdvisor(domainNames[i], domainDefinition.getManager(), this);
         Class c = Class.forName("org.jboss.jms.server.endpoint." + domainNames[i]);
         advisors[i].attachClass(c);

         // register the advisor with the Dispatcher
         Dispatcher.singleton.registerTarget(advisors[i].getName(), advisors[i]);
      }
      connFactoryAdvisor = advisors[0];
      connAdvisor = advisors[1];
      sessionAdvisor = advisors[2];
      producerAdvisor = advisors[3];
		consumerAdvisor = advisors[4];		
      browserAdvisor = advisors[5];
      genericAdvisor = advisors[6];
   }

   private void tearDownAdvisors() throws Exception
   {
      for(int i = 0; i < domainNames.length; i++)
      {
         Dispatcher.singleton.unregisterTarget(domainNames[i]);
      }
      connFactoryAdvisor = null;
      connAdvisor = null;
      sessionAdvisor = null;
      producerAdvisor = null;
      browserAdvisor = null;
   }

   private ConnectionFactory createConnectionFactory(String connFactoryID) throws Exception
   {
      ConnectionFactoryDelegate proxy = (ConnectionFactoryDelegate)createProxy(connFactoryID);
      return new JBossConnectionFactory(proxy);
   }

   private Object createProxy(String connFactoryID) throws Exception
   {
      Serializable oid = connFactoryAdvisor.getName();
      String stackName = "ConnectionFactoryStack";
      AdviceStack stack = AspectManager.instance().getAdviceStack(stackName);
      // TODO why do I need an advisor to create an interceptor stack?
      Interceptor[] interceptors = stack.createInterceptors(connFactoryAdvisor, null);
      JMSInvocationHandler h = new JMSInvocationHandler(interceptors);

      SimpleMetaData metadata = new SimpleMetaData();
      // TODO: The ConnectionFactoryDelegate and ConnectionDelegate share the same locator (TCP/IP connection?). Performance?
      metadata.addMetaData(Dispatcher.DISPATCHER, Dispatcher.OID, oid, PayloadKey.AS_IS);
      metadata.addMetaData(InvokerInterceptor.REMOTING,
                           InvokerInterceptor.INVOKER_LOCATOR,
                           locator,
                           PayloadKey.AS_IS);
      metadata.addMetaData(InvokerInterceptor.REMOTING,
                           InvokerInterceptor.INVOKER_LOCATOR,
                           locator,
                           PayloadKey.AS_IS);
      metadata.addMetaData(InvokerInterceptor.REMOTING,
                           InvokerInterceptor.SUBSYSTEM,
                           "JMS",
                           PayloadKey.AS_IS);
      metadata.addMetaData(JMSAdvisor.JMS, JMSAdvisor.CONNECTION_FACTORY_ID, connFactoryID, PayloadKey.AS_IS);
            
      h.getMetaData().mergeIn(metadata);

      // TODO 
      ClassLoader loader = getClass().getClassLoader();
      Class[] interfaces = new Class[] { ConnectionFactoryDelegate.class };
      return Proxy.newProxyInstance(loader, interfaces, h);
   }

   
   /**
    * @return - may return null if it doesn't find a "jboss" MBeanServer.
    */
   private MBeanServer findMBeanServer()
   {
      System.setProperty("jmx.invoke.getters", "true");
      
      
      MBeanServer result = null;
      ArrayList l = MBeanServerFactory.findMBeanServer(null);
      for(Iterator i = l.iterator(); i.hasNext(); )
      {
         MBeanServer s = (MBeanServer)l.iterator().next();
         if ("jboss".equals(s.getDefaultDomain()))
         {
            result = s;
            break;
         }
      }
      return result;
   }

   private void initializeRemoting() throws Exception
   {
      String s = (String)mbeanServer.invoke(connector, "getInvokerLocator",
                                            new Object[0], new String[0]);
      locator = new InvokerLocator(s);

      log.debug("LocatorURI: " + getLocatorURI());

      // add the JMS subsystem
      mbeanServer.invoke(connector, "addInvocationHandler",
                         new Object[] {"JMS", new JMSServerInvocationHandler()},
                         new String[] {"java.lang.String",
                                       "org.jboss.remoting.ServerInvocationHandler"});

      // TODO what happens if there is a JMS subsystem already registered? Normally, nothing bad,
      // TODO since it delegates to a static dispatcher, but make sure

      // TODO if this is ServerPeer is stopped, the InvocationHandler will be left hanging
   }

   private TransactionManager findTransactionManager() throws Exception
   {
      TransactionManager tm = null;
      try
      {
         tm = (TransactionManager)initialContext.lookup("java:/TransactionManager");
      }
      catch(NameNotFoundException e)
      {}

//      if (tm == null)
//      {
//         tm = TransactionManagerImpl.getInstance();
//         log.warn("Cannot find a transaction manager, using an internal implementation!");
//      }

      log.debug("TransactionManager: " + tm);
      return tm;
   }


   private void setupConnectionFactories()
      throws Exception
   {

      ConnectionFactory cf = setupConnectionFactory(null);
      initialContext.rebind(CONNECTION_FACTORY_JNDI_NAME, cf);
      initialContext.rebind(XACONNECTION_FACTORY_JNDI_NAME, cf);
      
      //And now the connection factories and links as required by the TCK 
      //See section 4.4.15 of the TCK user guide.
      //FIXME - this is a hack. It should be removed once a better way to manage
      //connection factories is implemented
      
      Context jmsContext = JNDIUtil.createContext(initialContext, "jms");
      jmsContext.rebind("QueueConnectionFactory", cf);
      jmsContext.rebind("TopicConnectionFactory", cf);
      
      jmsContext.rebind("DURABLE_SUB_CONNECTION_FACTORY", setupConnectionFactory("cts"));
      jmsContext.rebind("MDBTACCESSTEST_FACTORY", setupConnectionFactory("cts1"));
      jmsContext.rebind("DURABLE_BMT_CONNECTION_FACTORY", setupConnectionFactory("cts2"));
      jmsContext.rebind("DURABLE_CMT_CONNECTION_FACTORY", setupConnectionFactory("cts3"));
      jmsContext.rebind("DURABLE_BMT_XCONNECTION_FACTORY", setupConnectionFactory("cts4"));
      jmsContext.rebind("DURABLE_CMT_XCONNECTION_FACTORY", setupConnectionFactory("cts5"));
      jmsContext.rebind("DURABLE_CMT_TXNS_XCONNECTION_FACTORY", setupConnectionFactory("cts6"));
    
   }
   
   private ConnectionFactory setupConnectionFactory(String clientID)
      throws Exception
   {
      String connFactoryID = genConnFactoryID();
      ServerConnectionFactoryDelegate serverDelegate = new ServerConnectionFactoryDelegate(this, clientID);
      this.connFactoryDelegates.put(connFactoryID, serverDelegate);      
      ConnectionFactory clientDelegate = createConnectionFactory(connFactoryID);
      return clientDelegate;
   }
   
   private void tearDownConnectionFactories()
      throws Exception
   {
      
      //FIXME - this is a hack. It should be removed once a better way to manage
      //connection factories is implemented
      initialContext.unbind("jms/DURABLE_SUB_CONNECTION_FACTORY");
      initialContext.unbind("jms/MDBTACCESSTEST_FACTORY");
      initialContext.unbind("jms/DURABLE_BMT_CONNECTION_FACTORY");
      initialContext.unbind("jms/DURABLE_CMT_CONNECTION_FACTORY");
      initialContext.unbind("jms/DURABLE_BMT_XCONNECTION_FACTORY");
      initialContext.unbind("jms/DURABLE_CMT_XCONNECTION_FACTORY");
      initialContext.unbind("jms/DURABLE_CMT_TXNS_XCONNECTION_FACTORY");
      
      initialContext.unbind(CONNECTION_FACTORY_JNDI_NAME);
      initialContext.unbind(XACONNECTION_FACTORY_JNDI_NAME);
     
   }
   
 
   
   private synchronized String genConnFactoryID()
   {
      return "CONNFACTORY" + connFactoryIDSequence++;
   }
   

   

   // Inner classes -------------------------------------------------
}
