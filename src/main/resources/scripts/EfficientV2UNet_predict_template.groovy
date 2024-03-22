import qupath.ext.efficientv2unet.EfficientV2UNet

// TODO adapt description

/**
 * Cellpose Detection Template script
 * @author Olivier Burri
 *
 * This script is a template to detect objects using a Cellpose model from within QuPath.
 * After defining the builder, it will:
 * 1. Find all selected annotations in the current open ImageEntry
 * 2. Export the selected annotations to a temp folder that can be specified with tempDirectory()
 * 3. Run the cellpose detction using the defined model name or path
 * 4. Reimport the mask images into QuPath and create the desired objects with the selected statistics
 *
 * NOTE: that this template does not contain all options, but should help get you started
 * See all options in https://biop.github.io/qupath-extension-cellpose/qupath/ext/biop/cellpose/CellposeBuilder.html
 * and in https://cellpose.readthedocs.io/en/latest/command.html
 *
 * NOTE 2: You should change pathObjects get all annotations if you want to run for the project. By default this script
 * will only run on the selected annotations.
 */

def model_path = "/Users/loic/Desktop/_________QuPathMartin/models/b3_Ntrain66_Nval14_Ntest14_epochs200/b3_Ntrain66_Nval14_Ntest14_epochs200_best-ckp.h5"
def efficientV2Unet = EfficientV2UNet
        .builder(model_path)
    // General settings
//      .doTrain(false)                                         // either doTrain or doPredict must be true
        .doPredict(true)                                // either doTrain or doPredict must be true
    // Train settings
//      .setTrainImageDirectory("/path/to/image/dir")
//      .setTrainMaskDirectory("path/to/mask/dir")
//      .SetBaseDirectory("path/to/model/saving/dir")
//      .setName("myEfficientV2UNet_model")
//      .setEpochs(200)

    // Predict settings
//        .setTempDir("path/to/folder")                         // Defaults to "../YourQuPathProjectFolder/temp"
//        .setPredictOutputDirectory("path/to/another/folder")  // Defaults to "../YourQuPathProjectFolder/temp/predictions"
        .setResolution(2)                                       // Resolution at which the prediction should be done (1=full, 2=half, ect.). Defaults to 1
        .setThreshold(0.2)
//        .setUseLessMemory(true)                               // should keep this true FIXME - probably best to remove this from here
        .setAnnotationClassName("Region")                       // Annotation class name for the detected objects. Defaults to "Region"
        .doSplitObject(false)                             // Whether to split the detected objects into separate annotations. Defaults to false
        .doRemoveExistingAnnotations(false)             // Whether to remove existing annotations in the image (!Removes all objects!). Defaults to false
        .build()

// start the prediction
efficientV2Unet.process()
println("Script done")