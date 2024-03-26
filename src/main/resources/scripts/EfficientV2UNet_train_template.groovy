import qupath.ext.efficientv2unet.EfficientV2UNet


/**
 * Efficient V2 UNet template script to train a model
 * @author Loïc Sauteur
 *
 * This script will train a model using image-mask paris that have been saved to respective folders.
 * After defining the builder, train and save the model.
 * Basically doing the same as via the command line
 */

def efficientV2Unet = EfficientV2UNet.builder()
// Modality settings
        .doTrain(true)                                         // either doTrain or doPredict must be true
//        .doPredict(false)                                // either doTrain or doPredict must be true
// Train settings
        .setTrainImageDirectory("/path/to/image/dir")           // Directory where the images are located (must be existing folder with tif files)
        .setTrainMaskDirectory("path/to/mask/dir")              // Directory where the masks are located (must be existing folder with tif files)
//        .setBaseDirectory("path/to/model/saving/dir")           // Saving path of the model: defaults to "../YourQuPathProjectFolder/models"
        .setBasemodel("b0")                                     // Basemodel to use. Defaults to "b0". Others: b1, b2, b3, s, m, l
        .setName("myEfficientV2UNet_model")                     // Name for the model. Defaults to "myEfficientV2UNet_*basemodel*"
        .setEpochs(100)                                         // Number of epochs. Defaults to 100

// Predict settings
//        .setModelPath(model_path)                               // Path to the trained .h5 model file
//        .setTempDir("path/to/folder")                         // Defaults to "../YourQuPathProjectFolder/temp"
//        .setPredictOutputDirectory("path/to/another/folder")  // Defaults to "../YourQuPathProjectFolder/temp/predictions"
//        .setResolution(2)                                       // Resolution at which the prediction should be done (1=full, 2=half, ect.). Defaults to 1
//        .setThreshold(0.2)
//        .setAnnotationClassName("Region")                       // Annotation class name for the detected objects. Defaults to "Region"
//        .doSplitObject(false)                             // Whether to split the detected objects into separate annotations. Defaults to false
//        .doRemoveExistingAnnotations(false)             // Whether to remove existing annotations in the image (!Removes all objects!). Defaults to false
        .build()

// start the training
efficientV2Unet.process()
println("Script done")