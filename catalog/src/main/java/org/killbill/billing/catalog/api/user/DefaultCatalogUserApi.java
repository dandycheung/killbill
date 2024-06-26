/*
 * Copyright 2010-2014 Ning, Inc.
 * Copyright 2014-2020 Groupon, Inc
 * Copyright 2020-2022 Equinix, Inc
 * Copyright 2014-2022 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.catalog.api.user;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.xml.bind.JAXBException;
import javax.xml.transform.TransformerException;

import org.joda.time.DateTime;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.catalog.CatalogUpdater;
import org.killbill.billing.catalog.DefaultCatalogValidation;
import org.killbill.billing.catalog.DefaultVersionedCatalog;
import org.killbill.billing.catalog.StandaloneCatalog;
import org.killbill.billing.catalog.api.CatalogApiException;
import org.killbill.billing.catalog.api.CatalogService;
import org.killbill.billing.catalog.api.CatalogUserApi;
import org.killbill.billing.catalog.api.SimplePlanDescriptor;
import org.killbill.billing.catalog.api.StaticCatalog;
import org.killbill.billing.catalog.api.CatalogValidation;
import org.killbill.billing.catalog.api.VersionedCatalog;
import org.killbill.billing.catalog.caching.CatalogCache;
import org.killbill.billing.tenant.api.TenantApiException;
import org.killbill.billing.tenant.api.TenantKV.TenantKey;
import org.killbill.billing.tenant.api.TenantUserApi;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.InternalCallContextFactory;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.clock.Clock;
import org.killbill.xmlloader.ValidationError;
import org.killbill.xmlloader.ValidationErrors;
import org.killbill.xmlloader.ValidationException;
import org.killbill.xmlloader.XMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

public class DefaultCatalogUserApi implements CatalogUserApi {

    private final Logger logger = LoggerFactory.getLogger(DefaultCatalogUserApi.class);

    private final CatalogService catalogService;
    private final InternalCallContextFactory internalCallContextFactory;
    private final TenantUserApi tenantApi;
    private final CatalogCache catalogCache;
    private final Clock clock;

    @Inject
    public DefaultCatalogUserApi(final CatalogService catalogService,
                                 final TenantUserApi tenantApi,
                                 final CatalogCache catalogCache,
                                 final Clock clock,
                                 final InternalCallContextFactory internalCallContextFactory) {
        this.catalogService = catalogService;
        this.tenantApi = tenantApi;
        this.catalogCache = catalogCache;
        this.clock = clock;
        this.internalCallContextFactory = internalCallContextFactory;
    }

    @Override
    public VersionedCatalog getCatalog(final String catalogName, final TenantContext tenantContext) throws CatalogApiException {
        final InternalTenantContext internalTenantContext;
        if (tenantContext.getAccountId() != null) {
            internalTenantContext = internalCallContextFactory.createInternalTenantContext(tenantContext.getAccountId(), tenantContext);
        } else {
            internalTenantContext = createInternalTenantContext(tenantContext);
        }
        final VersionedCatalog fullCatalog = catalogService.getFullCatalog(true, true, internalTenantContext);
        return fullCatalog;
    }

    @Override
    public StaticCatalog getCurrentCatalog(final String catalogName, final TenantContext tenantContext) throws CatalogApiException {
        final VersionedCatalog fullCatalog = getCatalog(catalogName, tenantContext);
        return fullCatalog.getCurrentVersion();
    }

    @Override
    public void uploadCatalog(final String catalogXML, final CallContext callContext) throws CatalogApiException {

        final InternalTenantContext internalTenantContext = createInternalTenantContext(callContext);
        try {

            final ValidationErrors errors = validateCatalogInternal(catalogXML, internalTenantContext);
            if (!errors.isEmpty()) {
                // Bummer ValidationException CTOR is private to package...
                //final ValidationException validationException = new ValidationException(errors);
                //throw new CatalogApiException(errors, ErrorCode.CAT_INVALID_FOR_TENANT, internalTenantContext.getTenantRecordId());
                logger.info("Failed to load new catalog version: " + errors.toString());
                throw new CatalogApiException(ErrorCode.CAT_INVALID_FOR_TENANT, internalTenantContext.getTenantRecordId());
            }

            tenantApi.addTenantKeyValue(TenantKey.CATALOG.toString(), catalogXML, callContext);
            catalogCache.clearCatalog(internalTenantContext);
        } catch (final TenantApiException e) {
            throw new CatalogApiException(e);
        }
    }

    @Override
    public CatalogValidation validateCatalog(final String catalogXML, final CallContext context) {
        final InternalTenantContext internalTenantContext = createInternalTenantContext(context);
        final ValidationErrors errors = validateCatalogInternal(catalogXML, internalTenantContext);
        if (!errors.isEmpty()) {
            logger.info("Failed to load new catalog version: " + errors.toString());
        }
        return new DefaultCatalogValidation(errors);
    }

    @Override
    public void createDefaultEmptyCatalog(@Nullable final DateTime effectiveDate, final CallContext callContext) throws CatalogApiException {

        try {
            final InternalTenantContext internalTenantContext = internalCallContextFactory.createInternalTenantContextWithoutAccountRecordId(callContext);
            final StandaloneCatalog currentCatalog = getCurrentStandaloneCatalogForTenant(internalTenantContext);
            final CatalogUpdater catalogUpdater = (currentCatalog != null) ?
                                                  new CatalogUpdater(currentCatalog) :
                                                  new CatalogUpdater(getSafeFirstCatalogEffectiveDate(effectiveDate, callContext), null);

            tenantApi.updateTenantKeyValue(TenantKey.CATALOG.toString(), catalogUpdater.getCatalogXML(internalTenantContext), callContext);
            catalogCache.clearCatalog(internalTenantContext);
        } catch (TenantApiException e) {
            throw new CatalogApiException(e);
        }
    }

    @Override
    public void addSimplePlan(final SimplePlanDescriptor descriptor, @Nullable final DateTime effectiveDate, final CallContext callContext) throws CatalogApiException {

        try {
            final InternalTenantContext internalTenantContext = internalCallContextFactory.createInternalTenantContextWithoutAccountRecordId(callContext);
            final StandaloneCatalog currentCatalog = getCurrentStandaloneCatalogForTenant(internalTenantContext);
            final CatalogUpdater catalogUpdater = (currentCatalog != null) ?
                                                  new CatalogUpdater(currentCatalog) :
                                                  new CatalogUpdater(getSafeFirstCatalogEffectiveDate(effectiveDate, callContext), descriptor.getCurrency());
            catalogUpdater.addSimplePlanDescriptor(descriptor);

            tenantApi.updateTenantKeyValue(TenantKey.CATALOG.toString(), catalogUpdater.getCatalogXML(internalTenantContext), callContext);
            catalogCache.clearCatalog(internalTenantContext);
        } catch (TenantApiException e) {
            throw new CatalogApiException(e);
        }
    }

    @Override
    public void deleteCatalog(final CallContext callContext) throws CatalogApiException {

        final InternalTenantContext internalTenantContext = internalCallContextFactory.createInternalTenantContextWithoutAccountRecordId(callContext);
        try {
            tenantApi.deleteTenantKey(TenantKey.CATALOG.toString(), callContext);
            catalogCache.clearCatalog(internalTenantContext);
            createDefaultEmptyCatalog(callContext.getCreatedDate(), callContext);
        } catch (final TenantApiException e) {
            throw new CatalogApiException(e);
        }
    }

    private DateTime getSafeFirstCatalogEffectiveDate(@Nullable final DateTime input, final CallContext callContext) {
        // The effectiveDate for the initial version does not matter too much
        // Because of #760, we want to make that client passing a approximate date (e.g today.toDateTimeAtStartOfDay()) will find the version
        final DateTime catalogEffectiveDate = callContext.getCreatedDate().minusDays(1);
        return (input == null || input.isAfter(catalogEffectiveDate)) ? catalogEffectiveDate : input;
    }

    private StandaloneCatalog getCurrentStandaloneCatalogForTenant(final InternalTenantContext internalTenantContext) throws CatalogApiException {
        final VersionedCatalog versionedCatalog = catalogService.getFullCatalog(false, false, internalTenantContext);
        if (versionedCatalog != null) {
            final StandaloneCatalog standaloneCatalogWithPriceOverride = (StandaloneCatalog) versionedCatalog.getCurrentVersion();
            return standaloneCatalogWithPriceOverride;
        } else {
            return null;
        }
    }

    private InternalTenantContext createInternalTenantContext(final TenantContext tenantContext) {
        // Only tenantRecordId will be populated -- this is important to always create the (ehcache) key the same way
        return internalCallContextFactory.createInternalTenantContextWithoutAccountRecordId(tenantContext);
    }

    private ValidationErrors validateCatalogInternal(final String catalogXML, final InternalTenantContext internalTenantContext) {
        final ValidationErrors errors = new ValidationErrors();
        try {
            VersionedCatalog versionedCatalog = catalogService.getFullCatalog(false, true, internalTenantContext);

            //clone the catalog just to be safe, since the validation process adds the catalog to be validated to the versionedCatalog
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final ObjectOutput out = new ObjectOutputStream(bos);
            out.writeObject(versionedCatalog);
            final ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
            final ObjectInputStream in = new ObjectInputStream(bis);
            versionedCatalog = (VersionedCatalog) in.readObject();

            if (versionedCatalog == null) {
                versionedCatalog = new DefaultVersionedCatalog();
            }
            // Validation purpose:  Will throw if bad XML or catalog validation fails
            final InputStream stream = new ByteArrayInputStream(catalogXML.getBytes(StandardCharsets.UTF_8));
            final StaticCatalog newCatalogVersion = XMLLoader.getObjectFromStream(stream, StandaloneCatalog.class);

            if (versionedCatalog.getCatalogName() != null && !versionedCatalog.getCatalogName().isEmpty() && !newCatalogVersion.getCatalogName().equals(versionedCatalog.getCatalogName())) {
                errors.add(new ValidationError(String.format("Catalog name '%s' is different from existing catalog name '%s'", newCatalogVersion.getCatalogName(), versionedCatalog.getCatalogName()), StaticCatalog.class, ""));
                return errors;
            }

            ((DefaultVersionedCatalog) versionedCatalog).add((StandaloneCatalog) newCatalogVersion);
            ((DefaultVersionedCatalog) versionedCatalog).validate(null, errors);

        } catch (final CatalogApiException e) {
            errors.add(new ValidationError(e.getMessage(), DefaultVersionedCatalog.class, ""));
        } catch (final ValidationException e) {
            errors.addAll(e.getErrors());
        } catch (final JAXBException e) {
            errors.add(new ValidationError(e.getLinkedException() != null ? e.getLinkedException().getMessage() : e.getMessage(), DefaultVersionedCatalog.class, ""));
        } catch (final TransformerException | IOException | SAXException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        return errors;
    }
}
