/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.core;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.AcknowledgedRequest;
import org.elasticsearch.cluster.*;
import org.elasticsearch.cluster.ack.ClusterStateUpdateResponse;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.core.ESLicenses;
import org.elasticsearch.license.core.LicenseBuilders;
import org.elasticsearch.license.manager.ESLicenseManager;
import org.elasticsearch.license.plugin.action.delete.DeleteLicenseAction;
import org.elasticsearch.license.plugin.action.delete.DeleteLicenseRequest;
import org.elasticsearch.license.plugin.action.put.PutLicenseRequest;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.internal.InternalNode;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static org.elasticsearch.license.plugin.core.TrialLicensesBuilder.EMPTY;

/**
 * Service responsible for maintaining and providing access to licenses on nodes.
 *
 * TODO: Work in progress:
 *  - implement logic in clusterChanged
 *  - interface with LicenseManager
 */
public class LicensesService extends AbstractLifecycleComponent<LicensesService> implements ClusterStateListener, LicensesManagerService, LicensesValidatorService {

    private ESLicenseManager esLicenseManager;

    private InternalNode node;

    private ClusterService clusterService;

    private volatile TrialLicenses trialLicenses = EMPTY;

    @Inject
    public LicensesService(Settings settings, Node node) {
        super(settings);
        this.node = (InternalNode) node;
    }

    /**
     * Registers new licenses in the cluster
     * <p/>
     * This method can be only called on the master node. It tries to create a new licenses on the master
     * and if it was successful it adds the license to cluster metadata.
     */
    @Override
    public void registerLicenses(final PutLicenseRequestHolder requestHolder, final ActionListener<ClusterStateUpdateResponse> listener) {
        final PutLicenseRequest request = requestHolder.request;
        final ESLicenses newLicenses = request.license();
        clusterService.submitStateUpdateTask(requestHolder.source, new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(request, listener) {
            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                MetaData metaData = currentState.metaData();
                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                LicensesMetaData currentLicenses = metaData.custom(LicensesMetaData.TYPE);

                esLicenseManager.verifyLicenses(newLicenses);

                if (currentLicenses == null) {
                    // no licenses were registered
                    currentLicenses = new LicensesMetaData(newLicenses, null);
                } else {
                    // merge previous license with new one
                    ESLicenses mergedLicenses = LicenseBuilders.merge(currentLicenses.getLicenses(), newLicenses);
                    currentLicenses = new LicensesMetaData(mergedLicenses, currentLicenses.getTrialLicenses());
                }

                mdBuilder.putCustom(LicensesMetaData.TYPE, currentLicenses);
                return ClusterState.builder(currentState).metaData(mdBuilder).build();
            }
        });

    }

    @Override
    public void unregisterLicenses(final DeleteLicenseRequestHolder requestHolder, final ActionListener<ClusterStateUpdateResponse> listener) {
        final DeleteLicenseRequest request = requestHolder.request;
        final Set<ESLicenses.FeatureType> featuresToDelete = asFeatureTypes(request.features());
        clusterService.submitStateUpdateTask(requestHolder.source, new AckedClusterStateUpdateTask<ClusterStateUpdateResponse>(request, listener) {
            @Override
            protected ClusterStateUpdateResponse newResponse(boolean acknowledged) {
                return new ClusterStateUpdateResponse(acknowledged);
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                MetaData metaData = currentState.metaData();
                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                LicensesMetaData currentLicenses = metaData.custom(LicensesMetaData.TYPE);

                if (currentLicenses != null) {
                    //TODO: proper delete for trial licenses
                    final ESLicenses newLicenses = LicenseBuilders.removeFeatures(currentLicenses.getLicenses(), featuresToDelete);
                    currentLicenses = new LicensesMetaData(newLicenses, currentLicenses.getTrialLicenses());
                }
                mdBuilder.putCustom(LicensesMetaData.TYPE, currentLicenses);
                return ClusterState.builder(currentState).metaData(mdBuilder).build();
            }
        });
    }

    //TODO: hook this up
    private void registerTrialLicense(final TrialLicenses.TrialLicense trialLicense) {
        clusterService.submitStateUpdateTask("register trial license []", new ProcessedClusterStateUpdateTask() {
            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {

            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                MetaData metaData = currentState.metaData();
                MetaData.Builder mdBuilder = MetaData.builder(currentState.metaData());
                LicensesMetaData currentLicenses = metaData.custom(LicensesMetaData.TYPE);
                TrialLicensesBuilder newTrialLicenses = TrialLicensesBuilder.trialLicensesBuilder().license(trialLicense);
                if (currentLicenses != null) {
                    if (currentLicenses.getTrialLicenses() != null) {
                        // had previous trial licenses
                        newTrialLicenses = newTrialLicenses.licenses(currentLicenses.getTrialLicenses());
                        currentLicenses = new LicensesMetaData(currentLicenses.getLicenses(), newTrialLicenses.build());
                    } else {
                        // had no previous trial license
                        currentLicenses = new LicensesMetaData(currentLicenses.getLicenses(), newTrialLicenses.build());
                    }
                } else {
                    // had no license meta data
                    currentLicenses = new LicensesMetaData(null, newTrialLicenses.build());
                }
                mdBuilder.putCustom(LicensesMetaData.TYPE, currentLicenses);
                return ClusterState.builder(currentState).metaData(mdBuilder).build();
            }

            @Override
            public void onFailure(String source, @Nullable Throwable t) {

            }
        });
    }

    @Override
    protected void doStart() throws ElasticsearchException {
        clusterService = node.injector().getInstance(ClusterService.class);
        esLicenseManager = ESLicenseManager.createClusterStateBasedInstance(clusterService);

        if (DiscoveryNode.dataNode(settings) || DiscoveryNode.masterNode(settings)) {
            clusterService.add(this);
        }
    }

    @Override
    protected void doStop() throws ElasticsearchException {
    }

    @Override
    protected void doClose() throws ElasticsearchException {
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        //TODO

        // check for registered plugin
        // if appropriate registered plugin is found; push one-time trial license

        // check for cluster status (recovery)
        // switch validation enforcement
    }

    @Override
    public boolean checkLicenseExpiry(String feature) {
        //TODO make validation cluster state aware
        //check trial license existence
        // if found; use it to do the check

        return esLicenseManager.hasLicenseForFeature(ESLicenses.FeatureType.fromString(feature));
    }

    @Override
    public boolean checkMaxNode(String feature) {
        //TODO make validation cluster state aware
        return false;
    }

    private static Set<ESLicenses.FeatureType> asFeatureTypes(Set<String> featureTypeStrings) {
        Set<ESLicenses.FeatureType> featureTypes = new HashSet<>(featureTypeStrings.size());
        for (String featureString : featureTypeStrings) {
            featureTypes.add(ESLicenses.FeatureType.fromString(featureString));
        }
        return featureTypes;
    }

    public static class PutLicenseRequestHolder {
        private final PutLicenseRequest request;
        private final String source;

        public PutLicenseRequestHolder(PutLicenseRequest request, String source) {
            this.request = request;
            this.source = source;
        }
    }

    public static class DeleteLicenseRequestHolder {
        private final DeleteLicenseRequest request;
        private final String source;

        public DeleteLicenseRequestHolder(DeleteLicenseRequest request, String source) {
            this.request = request;
            this.source = source;
        }
    }

    private TrialLicenses.TrialLicense generateTrialLicense(String feature, int durationInDays, int maxNodes) {
        return TrialLicensesBuilder.trialLicenseBuilder()
                .issueDate(System.currentTimeMillis())
                .durationInDays(durationInDays)
                .feature(ESLicenses.FeatureType.fromString(feature))
                .maxNodes(maxNodes)
                .build();
    }

    private class PutTrialLicenseRequest extends AcknowledgedRequest<PutTrialLicenseRequest> {

        private PutTrialLicenseRequest() {
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            readTimeout(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            writeTimeout(out);
        }
    }
}
