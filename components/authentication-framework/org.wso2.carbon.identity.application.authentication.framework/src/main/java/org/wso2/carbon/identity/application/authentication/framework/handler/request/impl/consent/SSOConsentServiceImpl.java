/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.carbon.identity.application.authentication.framework.handler.request.impl.consent;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.consent.mgt.core.ConsentManager;
import org.wso2.carbon.consent.mgt.core.exception.ConsentManagementClientException;
import org.wso2.carbon.consent.mgt.core.exception.ConsentManagementException;
import org.wso2.carbon.consent.mgt.core.model.AddReceiptResponse;
import org.wso2.carbon.consent.mgt.core.model.ConsentPurpose;
import org.wso2.carbon.consent.mgt.core.model.PIICategory;
import org.wso2.carbon.consent.mgt.core.model.PIICategoryValidity;
import org.wso2.carbon.consent.mgt.core.model.Purpose;
import org.wso2.carbon.consent.mgt.core.model.PurposeCategory;
import org.wso2.carbon.consent.mgt.core.model.Receipt;
import org.wso2.carbon.consent.mgt.core.model.ReceiptInput;
import org.wso2.carbon.consent.mgt.core.model.ReceiptListResponse;
import org.wso2.carbon.consent.mgt.core.model.ReceiptPurposeInput;
import org.wso2.carbon.consent.mgt.core.model.ReceiptService;
import org.wso2.carbon.consent.mgt.core.model.ReceiptServiceInput;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.authentication.framework.exception.FrameworkException;
import org.wso2.carbon.identity.application.authentication.framework.internal.FrameworkServiceDataHolder;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.ServiceProvider;
import org.wso2.carbon.identity.application.common.model.User;
import org.wso2.carbon.identity.claim.metadata.mgt.ClaimMetadataManagementService;
import org.wso2.carbon.identity.claim.metadata.mgt.exception.ClaimMetadataException;
import org.wso2.carbon.identity.claim.metadata.mgt.model.LocalClaim;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.commons.collections.CollectionUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.EMPTY;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.wso2.carbon.consent.mgt.core.constant.ConsentConstants.ACTIVE_STATE;
import static org.wso2.carbon.consent.mgt.core.constant.ConsentConstants.ErrorMessages.ERROR_CODE_PII_CAT_NAME_INVALID;
import static org.wso2.carbon.consent.mgt.core.constant.ConsentConstants.ErrorMessages
        .ERROR_CODE_PURPOSE_CAT_NAME_INVALID;
import static org.wso2.carbon.consent.mgt.core.constant.ConsentConstants.ErrorMessages.ERROR_CODE_PURPOSE_NAME_INVALID;
import static org.wso2.carbon.identity.claim.metadata.mgt.util.ClaimConstants.DESCRIPTION_PROPERTY;
import static org.wso2.carbon.identity.claim.metadata.mgt.util.ClaimConstants.DISPLAY_NAME_PROPERTY;

/**
 * Implementation of {@link SSOConsentService}.
 */
public class SSOConsentServiceImpl implements SSOConsentService {

    private static final Log log = LogFactory.getLog(SSOConsentServiceImpl.class);
    private static final String DEFAULT_PURPOSE = "DEFAULT";
    private static final String DEFAULT_PURPOSE_CATEGORY = "DEFAULT";

    /**
     * Get consent required claims for a given service from a user.
     *
     * @param serviceProvider   Service provider requesting consent.
     * @param authenticatedUser Authenticated user requesting consent form.
     * @return ConsentClaimsData which contains mandatory and required claims for consent.
     * @throws FrameworkException If error occurs while building claim information.
     */
    public ConsentClaimsData getConsentRequiredClaims(ServiceProvider serviceProvider, AuthenticatedUser
            authenticatedUser) throws FrameworkException {

        if (serviceProvider == null) {
            throw new FrameworkException("Service provider cannot be null.");
        }

        String spName = serviceProvider.getApplicationName();

        String spTenantDomain;
        spTenantDomain = getSPTenantDomain(serviceProvider);

        String subject = buildSubjectWithUserStoreDomain(authenticatedUser);
        ConsentClaimsData consentClaimsData = new ConsentClaimsData();

        ClaimMapping[] claimMappings = getSpClaimMappings(serviceProvider);
        if (ArrayUtils.isEmpty(claimMappings)) {
            return consentClaimsData;
        }

        List<String> requestedClaims = new ArrayList<>();
        List<String> mandatoryClaims = new ArrayList<>();

        for (ClaimMapping claimMapping : claimMappings) {

            if (claimMapping.isMandatory()) {
                mandatoryClaims.add(claimMapping.getLocalClaim().getClaimUri());
            } else if (claimMapping.isRequested()) {
                requestedClaims.add(claimMapping.getLocalClaim().getClaimUri());
            }
        }

        List<ReceiptListResponse> receiptListResponses = getReceiptOfUser(serviceProvider, authenticatedUser,
                                                                          spName, spTenantDomain, subject);
        if (hasUserSingleReceipts(receiptListResponses)) {

            String receiptId = getFirstConsentReceiptFromList(receiptListResponses);
            Receipt receipt = getReceipt(authenticatedUser, receiptId);

            List<ClaimMetaData> receiptConsentMetaData = getConsentClaimsFromReceipt(receipt);
            List<String> claimsWithConsent = getClaimsFromConsentMetaData(receiptConsentMetaData);
            mandatoryClaims.removeAll(claimsWithConsent);
            // Only request consent for mandatory claims without consent when a receipt already exist for the user.
            requestedClaims.clear();
        }
        consentClaimsData = getConsentRequiredClaimData(mandatoryClaims, requestedClaims, spTenantDomain);
        return consentClaimsData;
    }

    private ClaimMapping[] getSpClaimMappings(ServiceProvider serviceProvider) {
        if (serviceProvider.getClaimConfig() != null) {
            return serviceProvider.getClaimConfig().getClaimMappings();
        } else {
            return new ClaimMapping[0];
        }
    }

    private String getSPTenantDomain(ServiceProvider serviceProvider) {
        String spTenantDomain;
        User owner = serviceProvider.getOwner();
        if (owner != null) {
            spTenantDomain = owner.getTenantDomain();
        } else {
            spTenantDomain = MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
        }
        return spTenantDomain;
    }

    /**
     * Process the provided user consent and creates a consent receipt.
     *
     * @param consentApprovedClaimIds   Consent approved claims by the user.
     * @param serviceProvider           Service provider receiving consent.
     * @param authenticatedUser         Authenticated user providing consent.
     * @param consentClaimsData         Claims which the consent requested for.
     * @throws FrameworkException If error occurs while processing user consent.
     */
    public void processConsent(List<Integer> consentApprovedClaimIds, ServiceProvider serviceProvider,
                                         AuthenticatedUser authenticatedUser, ConsentClaimsData consentClaimsData)
            throws FrameworkException {

        if (isDebugEnabled()) {
            logDebug("User: " + authenticatedUser.getAuthenticatedSubjectIdentifier() + " has approved consent.");
        }
        UserConsent userConsent = processUserConsent(consentApprovedClaimIds, consentClaimsData);
        String subject = buildSubjectWithUserStoreDomain(authenticatedUser);
        List<ClaimMetaData> claimsWithConsent = getAllUserApprovedClaims(serviceProvider, authenticatedUser,
                                                                         userConsent);
        String spTenantDomain = getSPTenantDomain(serviceProvider);
        String subjectTenantDomain = authenticatedUser.getTenantDomain();

        if (isNotEmpty(claimsWithConsent)) {
            addReceipt(subject, subjectTenantDomain, serviceProvider, spTenantDomain, claimsWithConsent);
        }
    }

    /**
     * Retrieves claims which a user has provided consent for a given service provider.
     *
     * @param serviceProvider   Service provider to retrieve the consent against.
     * @param authenticatedUser Authenticated user to related to consent claim retrieval.
     * @return List of claim which the user has provided consent for the given service provider.
     * @throws FrameworkException If error occurs while retrieve user consents.
     */
    public List<ClaimMetaData> getClaimsWithConsents(ServiceProvider serviceProvider, AuthenticatedUser
            authenticatedUser) throws FrameworkException {

        if (serviceProvider == null) {
            throw new FrameworkException("Service provider cannot be null.");
        }
        String spName = serviceProvider.getApplicationName();
        List<ClaimMetaData> receiptConsentMetaData = new ArrayList<>();

        String spTenantDomain = getSPTenantDomain(serviceProvider);

        String subject = buildSubjectWithUserStoreDomain(authenticatedUser);

        List<ReceiptListResponse> receiptListResponses = getReceiptOfUser(serviceProvider, authenticatedUser,
                                                                          spName, spTenantDomain, subject);
        if (hasUserNoReceipts(receiptListResponses)) {
            return receiptConsentMetaData;
        } else {
            String receiptId = getFirstConsentReceiptFromList(receiptListResponses);
            Receipt receipt = getReceipt(authenticatedUser, receiptId);

            receiptConsentMetaData = getConsentClaimsFromReceipt(receipt);
        }
        return receiptConsentMetaData;
    }

    private List<ReceiptListResponse> getReceiptOfUser(ServiceProvider serviceProvider,
                                                       AuthenticatedUser authenticatedUser, String spName,
                                                       String spTenantDomain, String subject) throws FrameworkException {

        int receiptListLimit = 2;
        List<ReceiptListResponse> receiptListResponses;
        try {
            receiptListResponses = getReceiptListOfUserForSP(authenticatedUser, spName, spTenantDomain, subject,
                                                         receiptListLimit);
            if (isDebugEnabled()) {
                String message = String.format("Retrieved %s receipts for user: %s, service provider: %s in tenant " +
                                               "domain %s", receiptListResponses.size(), subject, serviceProvider,
                                               spTenantDomain);
                logDebug(message);
            }

            if (hasUserMultipleReceipts(receiptListResponses)) {
                throw new FrameworkException("Consent Management Error", "User cannot have more than one ACTIVE " +
                                                                         "consent per service provider.");
            }
        } catch (ConsentManagementException e) {
            throw new FrameworkException("Consent Management Error", "Error while retrieving user consents.", e);
        }
        return receiptListResponses;
    }

    private AddReceiptResponse addReceipt(String subject, String subjectTenantDomain, ServiceProvider
            serviceProvider, String spTenantDomain, List<ClaimMetaData> claims) throws
            FrameworkException {

        ReceiptInput receiptInput = buildReceiptInput(subject, serviceProvider, spTenantDomain, claims);
        AddReceiptResponse receiptResponse;
        try {
            startTenantFlowWithUser(subject, subjectTenantDomain);
            receiptResponse = getConsentManager().addConsent(receiptInput);
        } catch (ConsentManagementException e) {
            throw new FrameworkException("Consent receipt error", "Error while adding the consent " +
                                                                                 "receipt", e);
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
        if (isDebugEnabled()) {
            logDebug("Successfully added consent receipt: " + receiptResponse.getConsentReceiptId());
        }
        return receiptResponse;
    }

    private ReceiptInput buildReceiptInput(String subject, ServiceProvider serviceProvider, String
            spTenantDomain, List<ClaimMetaData> claims) throws FrameworkException {

        String collectionMethod = "Web Form - Sign-in";
        String jurisdiction = "LK";
        String language = "us_EN";
        String consentType = "EXPLICIT";
        String termination = "DATE_UNTIL:INDEFINITE";
        String policyUrl = "http://nolink";

        Purpose purpose = getDefaultPurpose();
        PurposeCategory purposeCategory = getDefaultPurposeCategory();
        List<PIICategoryValidity> piiCategoryIds = getPiiCategoryValiditiesForClaims(claims, termination);
        List<ReceiptServiceInput> serviceInputs = new ArrayList<>();
        List<ReceiptPurposeInput> purposeInputs = new ArrayList<>();
        List<Integer> purposeCategoryIds = new ArrayList<>();
        Map<String, String> properties = new HashMap<>();

        purposeCategoryIds.add(purposeCategory.getId());

        ReceiptPurposeInput purposeInput = getReceiptPurposeInput(consentType, termination, purpose, piiCategoryIds,
                                                                  purposeCategoryIds);
        purposeInputs.add(purposeInput);

        ReceiptServiceInput serviceInput = getReceiptServiceInput(serviceProvider, spTenantDomain, purposeInputs);
        serviceInputs.add(serviceInput);

        return getReceiptInput(subject, collectionMethod, jurisdiction, language, policyUrl, serviceInputs, properties);
    }

    private ReceiptInput getReceiptInput(String subject, String collectionMethod, String jurisdiction, String
            language, String policyUrl, List<ReceiptServiceInput> serviceInputs, Map<String, String> properties) {

        ReceiptInput receiptInput = new ReceiptInput();
        receiptInput.setCollectionMethod(collectionMethod);
        receiptInput.setJurisdiction(jurisdiction);
        receiptInput.setLanguage(language);
        receiptInput.setPolicyUrl(policyUrl);
        receiptInput.setServices(serviceInputs);
        receiptInput.setProperties(properties);
        receiptInput.setPiiPrincipalId(subject);
        return receiptInput;
    }

    private ReceiptServiceInput getReceiptServiceInput(ServiceProvider serviceProvider, String spTenantDomain,
                                                              List<ReceiptPurposeInput> purposeInputs) {

        ReceiptServiceInput serviceInput = new ReceiptServiceInput();
        serviceInput.setPurposes(purposeInputs);
        serviceInput.setTenantDomain(spTenantDomain);

        if (serviceProvider == null) {
            return serviceInput;
        }
        String spName = serviceProvider.getApplicationName();
        String spDescription;
        spDescription = serviceProvider.getDescription();
        if (StringUtils.isBlank(spDescription)) {
            spDescription = spName;
        }
        serviceInput.setService(spName);
        serviceInput.setSpDisplayName(spDescription);
        serviceInput.setSpDescription(spDescription);
        return serviceInput;
    }

    private ReceiptPurposeInput getReceiptPurposeInput(String consentType, String termination, Purpose purpose,
                                                       List<PIICategoryValidity> piiCategoryIds,
                                                       List<Integer> purposeCategoryIds) {

        ReceiptPurposeInput purposeInput = new ReceiptPurposeInput();
        purposeInput.setPrimaryPurpose(true);
        purposeInput.setTermination(termination);
        purposeInput.setConsentType(consentType);
        purposeInput.setThirdPartyDisclosure(false);
        purposeInput.setPurposeId(purpose.getId());
        purposeInput.setPurposeCategoryId(purposeCategoryIds);
        purposeInput.setPiiCategory(piiCategoryIds);
        return purposeInput;
    }

    private List<PIICategoryValidity> getPiiCategoryValiditiesForClaims(List<ClaimMetaData> claims, String
            termination) throws FrameworkException {

        List<PIICategoryValidity> piiCategoryIds = new ArrayList<>();

        for (ClaimMetaData claim : claims) {
            PIICategory piiCategory;
            try {
                piiCategory = getConsentManager().getPIICategoryByName(claim.getClaimUri());
            } catch (ConsentManagementClientException e) {

                if (isInvalidPIICategoryError(e)) {
                    piiCategory = addPIICategoryForClaim(claim);
                } else {
                    throw new FrameworkException("Consent PII category error", "Error while retrieving" +
                            " PII category: " + DEFAULT_PURPOSE_CATEGORY, e);
                }
            } catch (ConsentManagementException e) {
                throw new FrameworkException("Consent PII category error", "Error while retrieving " +
                        "PII category: " + DEFAULT_PURPOSE_CATEGORY, e);
            }
            piiCategoryIds.add(new PIICategoryValidity(piiCategory.getId(), termination));
        }
        return piiCategoryIds;
    }

    private PIICategory addPIICategoryForClaim(ClaimMetaData claim) throws FrameworkException {

        PIICategory piiCategory;
        PIICategory piiCategoryInput = new PIICategory(claim.getClaimUri(), claim.getDescription(), false, claim
                .getDisplayName());
        try {
            piiCategory = getConsentManager().addPIICategory(piiCategoryInput);
        } catch (ConsentManagementException e) {
            throw new FrameworkException("Consent PII category error", "Error while adding" +
                                                                      " PII category:" + DEFAULT_PURPOSE_CATEGORY, e);
        }
        return piiCategory;
    }

    private boolean isInvalidPIICategoryError(ConsentManagementClientException e) {

        return ERROR_CODE_PII_CAT_NAME_INVALID.getCode().equals(e.getErrorCode());
    }

    private PurposeCategory getDefaultPurposeCategory() throws FrameworkException {

        PurposeCategory purposeCategory;
        try {
            purposeCategory = getConsentManager().getPurposeCategoryByName(DEFAULT_PURPOSE_CATEGORY);
        } catch (ConsentManagementClientException e) {

            if (isInvalidPurposeCategoryError(e)) {
                purposeCategory = addDefaultPurposeCategory();
            } else {
                throw new FrameworkException("Consent purpose category error", "Error while retrieving" +
                                                                  " purpose category: " + DEFAULT_PURPOSE_CATEGORY, e);
            }
        } catch (ConsentManagementException e) {
            throw new FrameworkException("Consent purpose category error", "Error while retrieving " +
                                                                  "purpose category: " + DEFAULT_PURPOSE_CATEGORY, e);
        }
        return purposeCategory;
    }

    private PurposeCategory addDefaultPurposeCategory() throws FrameworkException {

        PurposeCategory purposeCategory;
        PurposeCategory defaultPurposeCategory = new PurposeCategory(DEFAULT_PURPOSE_CATEGORY, "Core " +
                                                                                               "functionality");
        try {
            purposeCategory = getConsentManager().addPurposeCategory(defaultPurposeCategory);
        } catch (ConsentManagementException e) {
            throw new FrameworkException("Consent purpose category error", "Error while adding" +
                                                                  " purpose category: " + DEFAULT_PURPOSE_CATEGORY, e);
        }
        return purposeCategory;
    }

    private boolean isInvalidPurposeCategoryError(ConsentManagementClientException e) {

        return ERROR_CODE_PURPOSE_CAT_NAME_INVALID.getCode().equals(e.getErrorCode());
    }

    private Purpose getDefaultPurpose() throws FrameworkException {

        Purpose purpose;

        try {
            purpose = getConsentManager().getPurposeByName(DEFAULT_PURPOSE);
        } catch (ConsentManagementClientException e) {

            if (isInvalidPurposeError(e)) {
                purpose = addDefaultPurpose();
            } else {
                throw new FrameworkException("Consent purpose error", "Error while retrieving purpose: " +
                                                                                     DEFAULT_PURPOSE, e);
            }
        } catch (ConsentManagementException e) {
            throw new FrameworkException("Consent purpose error", "Error while retrieving purpose: " +
                                                                                 DEFAULT_PURPOSE, e);
        }
        return purpose;
    }

    private Purpose addDefaultPurpose() throws FrameworkException {

        Purpose purpose;
        Purpose defaultPurpose = new Purpose(DEFAULT_PURPOSE, "Core functionality");
        try {
            purpose = getConsentManager().addPurpose(defaultPurpose);
        } catch (ConsentManagementException e) {
            throw new FrameworkException("Consent purpose error", "Error while adding purpose: " + DEFAULT_PURPOSE, e);
        }
        return purpose;
    }

    private boolean isInvalidPurposeError(ConsentManagementClientException e) {

        return ERROR_CODE_PURPOSE_NAME_INVALID.getCode().equals(e.getErrorCode());
    }


    private UserConsent processUserConsent(List<Integer> consentApprovedClaimIds, ConsentClaimsData
            consentClaimsData) throws FrameworkException {

        UserConsent userConsent = new UserConsent();
        List<ClaimMetaData> approvedClamMetaData = buildApprovedClaimList(consentApprovedClaimIds, consentClaimsData);

        List<ClaimMetaData> consentRequiredClaimMetaData = getConsentRequiredClaimMetaData(consentClaimsData);
        List<ClaimMetaData> disapprovedClaims = buildDisapprovedClaimList(consentRequiredClaimMetaData,
                                                                          approvedClamMetaData);

        if (isMandatoryClaimsDisapproved(consentClaimsData.getMandatoryClaims(), disapprovedClaims)) {
            throw new FrameworkException("Consent Denied for Mandatory Attributes",
                                                        "User denied consent to share mandatory attributes.");
        }

        userConsent.setApprovedClaims(approvedClamMetaData);
        userConsent.setDisapprovedClaims(disapprovedClaims);

        return userConsent;
    }

    private List<ClaimMetaData> getAllUserApprovedClaims(ServiceProvider serviceProvider, AuthenticatedUser
            authenticatedUser, UserConsent userConsent) throws FrameworkException {

        List<ClaimMetaData> claimsWithConsent = new ArrayList<>();
        claimsWithConsent.addAll(userConsent.getApprovedClaims());

        String spName = serviceProvider.getApplicationName();

        String spTenantDomain = getSPTenantDomain(serviceProvider);

        String subject = buildSubjectWithUserStoreDomain(authenticatedUser);
        List<ReceiptListResponse> receiptListResponses = getReceiptOfUser(serviceProvider, authenticatedUser,
                                                                          spName, spTenantDomain, subject);
        if (hasUserNoReceipts(receiptListResponses)) {
            return claimsWithConsent;
        }

        String receiptId = getFirstConsentReceiptFromList(receiptListResponses);
        Receipt currentReceipt = getReceipt(authenticatedUser, receiptId);
        List<PIICategoryValidity> piiCategoriesFromServices = getPIICategoriesFromServices
                (currentReceipt.getServices());
        List<ClaimMetaData> claimsFromPIICategoryValidity = getClaimsFromPIICategoryValidity
                (piiCategoriesFromServices);
        claimsWithConsent.addAll(claimsFromPIICategoryValidity);
        return claimsWithConsent;
    }

    private List<ClaimMetaData> getConsentRequiredClaimMetaData(ConsentClaimsData consentClaimsData) {

        List<ClaimMetaData> consentRequiredClaims = new ArrayList<>();

        if (isNotEmpty(consentClaimsData.getMandatoryClaims())) {
            consentRequiredClaims.addAll(consentClaimsData.getMandatoryClaims());
        }
        if (isNotEmpty(consentClaimsData.getRequestedClaims())) {
            consentRequiredClaims.addAll(consentClaimsData.getRequestedClaims());
        }
        return consentRequiredClaims;
    }

    private List<ClaimMetaData> buildDisapprovedClaimList(List<ClaimMetaData> consentRequiredClaims,
                                                           List<ClaimMetaData> approvedClaims) {

        List<ClaimMetaData> disapprovedClaims = new ArrayList<>();

        if (isNotEmpty(consentRequiredClaims)) {
            consentRequiredClaims.removeAll(approvedClaims);
            disapprovedClaims = consentRequiredClaims;
        }
        return disapprovedClaims;
    }

    private List<ClaimMetaData> buildApprovedClaimList(List<Integer> consentApprovedClaimIds, ConsentClaimsData
            consentClaimsData) {

        List<ClaimMetaData> approvedClaims = new ArrayList<>();

        for (Integer claimId : consentApprovedClaimIds) {
            ClaimMetaData consentClaim = new ClaimMetaData();
            consentClaim.setId(claimId);
            List<ClaimMetaData> mandatoryClaims = consentClaimsData.getMandatoryClaims();

            int claimIndex = mandatoryClaims.indexOf(consentClaim);
            if (claimIndex != -1) {
                approvedClaims.add(mandatoryClaims.get(claimIndex));
            }

            List<ClaimMetaData> requestedClaims = consentClaimsData.getRequestedClaims();
            claimIndex = requestedClaims.indexOf(consentClaim);
            if (claimIndex != -1) {
                approvedClaims.add(requestedClaims.get(claimIndex));
            }
        }
        return approvedClaims;
    }

    private String buildSubjectWithUserStoreDomain(AuthenticatedUser authenticatedUser) {

        return UserCoreUtil.addDomainToName(authenticatedUser.getUserName(), authenticatedUser.getUserStoreDomain());
    }

    private List<ReceiptListResponse> getReceiptListOfUserForSP(AuthenticatedUser authenticatedUser,
                                                                String serviceProvider, String spTenantDomain,
                                                                String subject, int limit) throws
            ConsentManagementException {

        List<ReceiptListResponse> receiptListResponses;
        startTenantFlowWithUser(subject, authenticatedUser.getTenantDomain());
        try {
            receiptListResponses = getConsentManager().searchReceipts(limit, 0, subject,
                                                                 spTenantDomain, serviceProvider, ACTIVE_STATE);
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
        return receiptListResponses;
    }

    private List<PIICategoryValidity> getPIICategoriesFromServices(List<ReceiptService> receiptServices) {

        List<PIICategoryValidity> piiCategoryValidityMap = new ArrayList<>();
        for (ReceiptService receiptService : receiptServices) {

            List<ConsentPurpose> purposes = receiptService.getPurposes();

            for (ConsentPurpose purpose : purposes) {
                piiCategoryValidityMap.addAll(piiCategoryValidityMap.size(), purpose.getPiiCategory());
            }
        }
        return piiCategoryValidityMap;
    }

    private List<ClaimMetaData> getConsentClaimsFromReceipt(Receipt receipt) {

        List<ReceiptService> services = receipt.getServices();
        List<PIICategoryValidity> piiCategories = getPIICategoriesFromServices(services);
        List<ClaimMetaData> claimsFromPIICategoryValidity = getClaimsFromPIICategoryValidity(piiCategories);
        if (isDebugEnabled()) {
            String message = String.format("User: %s has provided consent in receipt: %s for claims: " +
                                           claimsFromPIICategoryValidity, receipt.getPiiPrincipalId(),
                                           receipt.getConsentReceiptId());
            logDebug(message);
        }
        return claimsFromPIICategoryValidity;
    }

    private List<ClaimMetaData> getClaimsFromPIICategoryValidity(List<PIICategoryValidity> piiCategories) {

        List<ClaimMetaData> claimMetaDataList = new ArrayList<>();
        for (PIICategoryValidity piiCategoryValidity : piiCategories) {

            if (isConsentForClaimValid(piiCategoryValidity)) {

                ClaimMetaData claimMetaData = new ClaimMetaData();
                claimMetaData.setClaimUri(piiCategoryValidity.getName());
                claimMetaData.setDisplayName(piiCategoryValidity.getDisplayName());
                claimMetaDataList.add(claimMetaData);
            }
        }
        return claimMetaDataList;
    }

    protected boolean isConsentForClaimValid(PIICategoryValidity piiCategoryValidity) {

        return true;
    }

    private boolean isMandatoryClaimsDisapproved(List<ClaimMetaData> consentMandatoryClaims, List<ClaimMetaData>
            disapprovedClaims) {

        return isNotEmpty(consentMandatoryClaims) && !Collections.disjoint(disapprovedClaims, consentMandatoryClaims);
    }

    private ConsentClaimsData getConsentRequiredClaimData(Collection<String> mandatoryClaims, Collection<String>
            requestedClaims, String tenantDomain) throws FrameworkException{

        ConsentClaimsData consentClaimsData = new ConsentClaimsData();

        try {
            List<LocalClaim> localClaims = getClaimMetadataManagementService().getLocalClaims(tenantDomain);
            List<ClaimMetaData> mandatoryClaimsMetaData = new ArrayList<>();
            List<ClaimMetaData> requestedClaimsMetaData = new ArrayList<>();

            if (isNotEmpty(localClaims)) {
                int claimId = 0;
                for (LocalClaim localClaim : localClaims) {

                    if (isAllRequiredClaimsChecked(mandatoryClaims, requestedClaims, mandatoryClaimsMetaData,
                                                   requestedClaimsMetaData)) {
                        break;
                    }

                    String claimURI = localClaim.getClaimURI();
                    if (mandatoryClaims.contains(claimURI)) {
                        ClaimMetaData claimMetaData = buildClaimMetaData(claimId, localClaim, claimURI);
                        mandatoryClaimsMetaData.add(claimMetaData);
                        claimId++;
                    } else if(requestedClaims.contains(claimURI)) {
                        ClaimMetaData claimMetaData = buildClaimMetaData(claimId, localClaim, claimURI);
                        requestedClaimsMetaData.add(claimMetaData);
                        claimId++;
                    }
                }

                consentClaimsData.setMandatoryClaims(mandatoryClaimsMetaData);
                consentClaimsData.setRequestedClaims(requestedClaimsMetaData);
            }
        } catch (ClaimMetadataException e) {
            throw new FrameworkException("Error while retrieving local claims", "Error occurred while " +
                                                           "retrieving local claims for tenant: " + tenantDomain, e);
        }
        return consentClaimsData;
    }

    private ClaimMetaData buildClaimMetaData(int i, LocalClaim localClaim, String claimURI) {

        ClaimMetaData claimMetaData = new ClaimMetaData();
        claimMetaData.setId(i);
        claimMetaData.setClaimUri(claimURI);
        String displayName = localClaim.getClaimProperties().get(DISPLAY_NAME_PROPERTY);

        if (isNotBlank(displayName)) {
            claimMetaData.setDisplayName(displayName);
        } else {
            claimMetaData.setDisplayName(claimURI);
        }

        String description = localClaim.getClaimProperty(DESCRIPTION_PROPERTY);
        if (isNotBlank(description)) {
            claimMetaData.setDescription(description);
        } else {
            claimMetaData.setDescription(EMPTY);
        }
        return claimMetaData;
    }

    private List<String> getClaimsFromConsentMetaData(List<ClaimMetaData> claimMetaDataList) {

        List<String> claims = new ArrayList<>();
        for (ClaimMetaData claimMetaData : claimMetaDataList) {
            claims.add(claimMetaData.getClaimUri());
        }
        return claims;
    }

    private String getFirstConsentReceiptFromList(List<ReceiptListResponse> receiptListResponses) {

        return receiptListResponses.get(0).getConsentReceiptId();
    }

    private Receipt getReceipt(AuthenticatedUser authenticatedUser, String receiptId)
            throws FrameworkException {
        Receipt currentReceipt;
        String subject = buildSubjectWithUserStoreDomain(authenticatedUser);
        try {
            startTenantFlowWithUser(subject, authenticatedUser.getTenantDomain());
            currentReceipt = getConsentManager().getReceipt(receiptId);
        } catch (ConsentManagementException e) {
            throw new FrameworkException("Consent Management Error", "Error while retrieving user consents.", e);
        } finally {
            PrivilegedCarbonContext.endTenantFlow();
        }
        return currentReceipt;
    }

    private boolean isAllRequiredClaimsChecked(Collection<String> mandatoryClaims, Collection<String>
            requestedClaims, List<ClaimMetaData> mandatoryClaimsMetaData, List<ClaimMetaData> requestedClaimsMetaData) {

        return mandatoryClaims.size() + requestedClaims.size() == mandatoryClaimsMetaData.size() +
                                                                  requestedClaimsMetaData.size();
    }

    private boolean hasUserNoReceipts(List<ReceiptListResponse> receiptListResponses) {

        return receiptListResponses.size() == 0;
    }

    private boolean hasUserSingleReceipts(List<ReceiptListResponse> receiptListResponses) {

        return receiptListResponses.size() == 1;
    }

    private boolean hasUserMultipleReceipts(List<ReceiptListResponse> receiptListResponses) {

        return receiptListResponses.size() > 1;
    }

    private void startTenantFlowWithUser(String subject, String subjectTenantDomain) {

        startTenantFlow(subjectTenantDomain);
        PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(subject);
    }

    private void startTenantFlow(String tenantDomain) {

        PrivilegedCarbonContext.startTenantFlow();
        PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
    }


    private boolean isDebugEnabled() {

        return log.isDebugEnabled();
    }

    private void logDebug(String message) {

        log.debug(message);
    }

    private ConsentManager getConsentManager() {

        return FrameworkServiceDataHolder.getInstance().getConsentManager();
    }

    private ClaimMetadataManagementService getClaimMetadataManagementService() {

        return FrameworkServiceDataHolder.getInstance().getClaimMetadataManagementService();
    }
}