package com.adobe.examples.db.resourceprovider.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceProvider;
import org.apache.sling.api.resource.ResourceProviderFactory;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

@Component(
        metatype = true,
        label = "DB Resource Provider Factory",
        description = "OSGi Configuration for DB Resource Providers",
        configurationFactory = true,
        specVersion = "1.1",
        policy = ConfigurationPolicy.REQUIRE
)
@Service
@Properties({
        @Property(name = "datasource.name"),
        @Property(name = ResourceProvider.ROOTS),
        @Property(name = ResourceProvider.OWNS_ROOTS, boolValue = true, propertyPrivate = true)
})
public class DBResourceProviderFactory implements ResourceProviderFactory, ServiceTrackerCustomizer<DataSource, DataSource> {

    private static final Logger LOG = LoggerFactory.getLogger(DBResourceProviderFactory.class);

    private static final String FILTER_TEMPLATE = "(&(datasource.name=%s)(objectClass=javax.sql.DataSource))";

    private String dataSourceName;
    private DataSource dataSource;
    private ServiceTracker<DataSource, DataSource> dataSourceTracker;
    private ServiceReference<DataSource> dataSourceReference;
    private Connection connection;
    private String rootPath;
    private DBResourceDataFactory dataFactory;

    public ResourceProvider getResourceProvider(Map<String, Object> authenticationInfo) throws LoginException {
        try {
            LOG.info("Getting DBResourceProvider for {}", rootPath);
            return new DBResourceProvider(dataFactory);
        } catch (SQLException e) {
            throw new LoginException("Database initialization failed", e);
        }
    }

    public ResourceProvider getAdministrativeResourceProvider(Map<String, Object> authenticationInfo) throws LoginException {
        return getResourceProvider(authenticationInfo);
    }

    @Activate
    public void activate(BundleContext context, Map<String, Object> properties) throws InvalidSyntaxException {
        final ValueMapDecorator props = new ValueMapDecorator(properties);
        dataSourceName = props.get("datasource.name", String.class);
        rootPath = props.get(ResourceProvider.ROOTS, String.class);
        if (dataSourceName != null) {
            LOG.info("Activating DB Resource Provider Factory for DataSource named [{}]", dataSourceName);
            final String filterExpression = String.format(FILTER_TEMPLATE, dataSourceName);
            final Filter filter = context.createFilter(filterExpression);
            dataSourceTracker = new ServiceTracker<DataSource, DataSource>(context, filter, this);
            dataSourceTracker.open();
        } else {
            throw new IllegalStateException("Configuration is missing datasource.name property");
        }

        LOG.info("DB Resource Provider Factory for DataSource named [{}] activated", dataSourceName);
    }

    @Deactivate
    public void deactivate() {
        LOG.info("Deactivating DB Resource Provider Factory for DataSource [{}]", dataSourceName);
        dataSourceTracker.close();
        dataSourceTracker = null;
    }

    public DataSource addingService(final ServiceReference<DataSource> reference) {
        if (dataSource == null) {
            dataSourceReference = reference;
            final DataSource ds = reference.getBundle().getBundleContext().getService(reference);
            registerDataSource(ds);
            return ds;
        } else {
            LOG.info("A DataSource named [{}] is already bound. Ignoring new DataSource.", dataSourceName);
        }
        return null;
    }

    public void modifiedService(final ServiceReference<DataSource> reference, final DataSource ds) {
        if (dataSourceReference == reference) {
            LOG.info("Updating DataSource named [{}].", dataSourceName);
            unregisterDataSource();
            registerDataSource(ds);
        }
    }

    public void removedService(final ServiceReference<DataSource> reference, final DataSource service) {
        if (dataSourceReference == reference) {
            if (dataSourceTracker != null) { // prevent misleading log message on deactivate (dataSourceTracker.close())
                LOG.info("DataSource named [{}] has disappeared. Deactivating service.", dataSourceName);
            }
            unregisterDataSource();
            dataSourceReference = null;
        }
    }

    private void registerDataSource(final DataSource ds) {
        try {
            connection = ds.getConnection();
            dataSource = ds;
            dataFactory = new DBResourceDataFactory(connection, rootPath);
            LOG.info("Bound datasource named [{}]", dataSourceName);
        } catch (SQLException e) {
            LOG.error("Failed to create a DB connection for DataSource named [{}]", dataSourceName, e);
        }
    }

    private void unregisterDataSource() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            LOG.error("Failed to close DB connection", e);
        } finally {
            connection = null;
            dataSource = null;
            dataFactory = null;
        }
    }
}
