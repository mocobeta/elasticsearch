/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.action.get;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.license.core.ESLicenses;
import org.elasticsearch.license.core.LicenseBuilders;

import java.io.IOException;

import static org.elasticsearch.license.plugin.action.Utils.readGeneratedLicensesFrom;
import static org.elasticsearch.license.plugin.action.Utils.writeGeneratedLicensesTo;

public class GetLicenseResponse extends ActionResponse {

    private ESLicenses licenses = null;

    GetLicenseResponse() {
    }

    GetLicenseResponse(ESLicenses esLicenses) {
        this.licenses = esLicenses;
    }

    public ESLicenses licenses() {
        return (licenses != null) ? licenses : LicenseBuilders.licensesBuilder().build();
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        licenses = readGeneratedLicensesFrom(in);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        writeGeneratedLicensesTo(licenses, out);
    }

}