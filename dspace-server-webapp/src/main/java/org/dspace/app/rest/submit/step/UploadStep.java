/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.submit.step;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.List;

import org.apache.logging.log4j.Logger;
import org.dspace.app.rest.exception.UnprocessableEntityException;
import org.dspace.app.rest.model.ErrorRest;
import org.dspace.app.rest.model.patch.Operation;
import org.dspace.app.rest.model.step.DataUpload;
import org.dspace.app.rest.model.step.UploadBitstreamRest;
import org.dspace.app.rest.repository.WorkspaceItemRestRepository;
import org.dspace.app.rest.submit.AbstractRestProcessingStep;
import org.dspace.app.rest.submit.SubmissionService;
import org.dspace.app.rest.submit.UploadableStep;
import org.dspace.app.rest.submit.factory.PatchOperationFactory;
import org.dspace.app.rest.submit.factory.impl.PatchOperation;
import org.dspace.app.rest.utils.Utils;
import org.dspace.app.util.DCInputSet;
import org.dspace.app.util.DCInputsReader;
import org.dspace.app.util.DCInputsReaderException;
import org.dspace.app.util.SubmissionStepConfig;
import org.dspace.content.Bitstream;
import org.dspace.content.BitstreamFormat;
import org.dspace.content.Bundle;
import org.dspace.content.InProgressSubmission;
import org.dspace.content.Item;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.services.model.Request;
import org.springframework.web.multipart.MultipartFile;

/**
 * Upload step for DSpace Spring Rest. Expose information about the bitstream
 * uploaded for the in progress submission.
 *
 * @author Luigi Andrea Pascarelli (luigiandrea.pascarelli at 4science.it)
 */
public class UploadStep extends org.dspace.submit.step.UploadStep
        implements AbstractRestProcessingStep, UploadableStep {

    private static final Logger log = org.apache.logging.log4j.LogManager.getLogger(UploadStep.class);

    public static final String UPLOAD_STEP_METADATA_SECTION = "bitstream-metadata";

    private DCInputsReader inputReader;

    public UploadStep() throws DCInputsReaderException {
        inputReader = new DCInputsReader();
    }
    @Override
    public DataUpload getData(SubmissionService submissionService, InProgressSubmission obj,
                              SubmissionStepConfig config) throws Exception {

        DataUpload result = new DataUpload();
        List<Bundle> bundles = itemService.getBundles(obj.getItem(), Constants.CONTENT_BUNDLE_NAME);
        for (Bundle bundle : bundles) {
            for (Bitstream source : bundle.getBitstreams()) {
                UploadBitstreamRest b = submissionService.buildUploadBitstream(configurationService, source);
                result.getFiles().add(b);
            }
        }
        return result;
    }

    @Override
    public void doPatchProcessing(Context context, Request currentRequest, InProgressSubmission source, Operation op,
                                  SubmissionStepConfig stepConf) throws Exception {

        String instance = "";
        if ("remove".equals(op.getOp())) {
            if (op.getPath().contains(UPLOAD_STEP_METADATA_PATH)) {
                instance = UPLOAD_STEP_METADATA_OPERATION_ENTRY;
            } else if (op.getPath().contains(UPLOAD_STEP_ACCESSCONDITIONS_OPERATION_ENTRY)) {
                instance = UPLOAD_STEP_ACCESSCONDITIONS_OPERATION_ENTRY;
            } else {
                instance = UPLOAD_STEP_REMOVE_OPERATION_ENTRY;
            }
        } else if ("move".equals(op.getOp())) {
            if (op.getPath().contains(UPLOAD_STEP_METADATA_PATH)) {
                instance = UPLOAD_STEP_METADATA_OPERATION_ENTRY;
            } else {
                instance = UPLOAD_STEP_MOVE_OPERATION_ENTRY;
            }
        } else {
            if (op.getPath().contains(UPLOAD_STEP_ACCESSCONDITIONS_OPERATION_ENTRY)) {
                instance = UPLOAD_STEP_ACCESSCONDITIONS_OPERATION_ENTRY;
            } else {
                instance = UPLOAD_STEP_METADATA_OPERATION_ENTRY;
            }
        }
        PatchOperation<?> patchOperation = new PatchOperationFactory().instanceOf(instance, op.getOp());
        if (instance.equals(AbstractRestProcessingStep.UPLOAD_STEP_METADATA_OPERATION_ENTRY)) {
            DCInputSet inputConfig = inputReader.getInputsByFormName(UploadStep.UPLOAD_STEP_METADATA_SECTION);
            String[] split = patchOperation.getAbsolutePath(op.getPath()).split("/");
            String metadata = findMetadata(split);
            if (inputConfig.isFieldPresent(metadata)) {
                patchOperation.perform(context, currentRequest, source, op);
            } else {
                throw new UnprocessableEntityException("The attribute " + metadata + " does not present in section "
                                                                        + UploadStep.UPLOAD_STEP_METADATA_SECTION);
            }
        } else {
            patchOperation.perform(context, currentRequest, source, op);
        }
    }

    private String findMetadata(String[] metadata) {
        for (String s : metadata) {
            if (s.contains("dc.")) {
                return s;
            }
        }
        return null;
    }

    @Override
    public ErrorRest upload(Context context, SubmissionService submissionService, SubmissionStepConfig stepConfig,
                            InProgressSubmission wsi, MultipartFile file) {

        Bitstream source = null;
        BitstreamFormat bf = null;

        Item item = wsi.getItem();
        List<Bundle> bundles = null;
        try {
            // do we already have a bundle?
            bundles = itemService.getBundles(item, Constants.CONTENT_BUNDLE_NAME);

            InputStream inputStream = new BufferedInputStream(file.getInputStream());
            if (bundles.size() < 1) {
                // set bundle's name to ORIGINAL
                source = itemService.createSingleBitstream(context, inputStream, item, Constants.CONTENT_BUNDLE_NAME);
            } else {
                // we have a bundle already, just add bitstream
                source = bitstreamService.create(context, bundles.get(0), inputStream);
            }

            source.setName(context, Utils.getFileName(file));
            source.setSource(context, file.getOriginalFilename());

            // Identify the format
            bf = bitstreamFormatService.guessFormat(context, source);
            source.setFormat(context, bf);

            // Update to DB
            bitstreamService.update(context, source);
            itemService.update(context, item);

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            ErrorRest result = new ErrorRest();
            result.setMessage(e.getMessage());
            if (bundles != null && bundles.size() > 0) {
                result.getPaths().add(
                    "/" + WorkspaceItemRestRepository.OPERATION_PATH_SECTIONS + "/" + stepConfig.getId() + "/files/" +
                    bundles.get(0).getBitstreams().size());
            } else {
                result.getPaths()
                    .add("/" + WorkspaceItemRestRepository.OPERATION_PATH_SECTIONS + "/" + stepConfig.getId());
            }
            return result;
        }

        return null;
    }
}
