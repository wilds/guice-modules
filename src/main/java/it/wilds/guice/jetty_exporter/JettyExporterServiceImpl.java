/**
 * Copyright (C) 2011 Brian Ferris <bdferris@onebusaway.org>
 * Copyright (C) 2012 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package it.wilds.guice.jetty_exporter;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class JettyExporterServiceImpl {

  protected final static Logger _log = Logger.getLogger(JettyExporterServiceImpl.class.getSimpleName());

  private final List<Server> _servers = new ArrayList<Server>();

  private final List<ServletSource> _services;

  public JettyExporterServiceImpl(List<ServletSource> services) {
    _services = services;
  }

  @PostConstruct
  public void start() throws Exception {

    Map<Integer, List<ServletSource>> servicesByPort = groupServicesByPort(_services);

    for (int port : servicesByPort.keySet()) {

      Server server = new Server(port);

      List<Connector> connectors = new ArrayList<>();

      ServerConnector connector = new ServerConnector(server);
      connector.setPort(port);
      connectors.add(connector);

      if (this.getClass().getResource("/keystore.jks") != null) {
        HttpConfiguration https = new HttpConfiguration();
        https.addCustomizer(new SecureRequestCustomizer());
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(this.getClass().getResource("/keystore.jks").toExternalForm());
        if (this.getClass().getResource("/keystore.properties") != null) {
          Properties properties = new Properties();
          properties.load(this.getClass().getResourceAsStream("/keystore.properties"));
          sslContextFactory.setKeyStorePassword(properties.getProperty("store-password"));
          sslContextFactory.setKeyManagerPassword(properties.getProperty("manager-password"));
        }
        ServerConnector sslConnector = new ServerConnector(server, new SslConnectionFactory(sslContextFactory, "http/1.1"), new HttpConnectionFactory(https));
        sslConnector.setPort(port + 1);
        connectors.add(sslConnector);
        _log.info("HTTPS available on port " + (port + 1));
      } else {
        _log.warning("Error: SSL Connection not available, no keystore");
      }

      server.setConnectors(connectors.toArray(new Connector[0]));

      ServletContextHandler context = new ServletContextHandler();
      context.setContextPath("/");

      for (ServletSource service : servicesByPort.get(port)) {
        URL url = service.getUrl();
        _log.info("Bind servlet " + service.getClass().getName() + " to " + url);
        context.addServlet(new ServletHolder(service.getServlet()), url.getPath());
      }

      server.setHandler(context);

      _servers.add(server);
    }

    for (Server server : _servers) {
      server.start();
      _log.info("Server start on " + server.getURI());
    }
  }

  @PreDestroy
  public void stop() throws Exception {
    for (Server server : _servers) {
      server.stop();
    }
    _servers.clear();
  }

  /****
   * Private Methods
   ****/

  private Map<Integer, List<ServletSource>> groupServicesByPort(
      List<ServletSource> services) {

    Map<Integer, List<ServletSource>> servicesByPort = new HashMap<Integer, List<ServletSource>>();

    for (ServletSource service : services) {
      URL url = service.getUrl();
      int port = url.getPort();
      List<ServletSource> servicesForPort = servicesByPort.get(port);
      if (servicesForPort == null) {
        servicesForPort = new ArrayList<ServletSource>();
        servicesByPort.put(port, servicesForPort);
      }
      servicesForPort.add(service);
    }

    /**
     * Services with a URL port of -1 indicate that no port has been specified.
     * If URLs of this form are present, determining the port to use for these
     * services depends on what other services have been specified.
     */
    List<ServletSource> servicesWithDefaultPort = servicesByPort.remove(-1);
    if (servicesWithDefaultPort != null) {
      if (servicesByPort.isEmpty()) {
        /**
         * If no other services have been specified, we just use the default
         * port 80 for the services with a default port value.
         */
        servicesByPort.put(80, servicesWithDefaultPort);
      } else {
        /**
         * If other services HAVE been specified, we bind the default port
         * services to EACH of the ports mentioned by other services. A little
         * bit counter-intuitive, I agree, but it allows the user to change a
         * primary client or server port and have secondary services
         * automatically use that new port.
         */
        for (List<ServletSource> sources : servicesByPort.values()) {
          sources.addAll(servicesWithDefaultPort);
        }
      }
    }

    return servicesByPort;
  }

}
