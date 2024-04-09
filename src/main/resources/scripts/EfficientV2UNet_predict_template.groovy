import qupath.ext.efficientv2unet.EfficientV2UNet


/**
 * Efficient V2 UNet template script to predict an image using a trained model
 * @author Loïc Sauteur
 *
 * This script will predict the currently opened image in QuPath, using the specified model.
 * After defining the builder, it will:
 * 1. Export the image as tif to the Temp directory
 * 2. Run the Efficient V2 UNet on this image and save the predicted image to the "Predict output" directory
 * 3. Reimport the mask image into QuPath and add the predicted object as annotation of the class "AnnotationClassName"
 * 4. Delete the temp file and the predicted image (but keep the folders)
 *
 */

def model_path = "/path/to/your/model/model_file.h5"
def efficientV2Unet = EfficientV2UNet.builder()
        // Modality settings
        .doPredict(true)                                // either doTrain or doPredict must be true

        // Predict settings
        .setModelPath(model_path)                               // Path to the trained .h5 model file
//        .setTempDir("path/to/folder")                         // Defaults to "../YourQuPathProjectFolder/temp"
//        .setPredictOutputDirectory("path/to/another/folder")  // Defaults to "../YourQuPathProjectFolder/temp/predictions"
        .setResolution(1)                                       // Resolution at which the prediction should be done (1=full, 2=half, ect.). Defaults to 1
        .setThreshold(0.5)
        .setAnnotationClassName("Region")                       // Annotation class name for the detected objects. Defaults to "Region"
        .doSplitObject(false)                             // Whether to split the detected objects into separate annotations. Defaults to false
        .doRemoveExistingAnnotations(false)             // Whether to remove existing annotations in the image (!Removes all objects!). Defaults to false
        .build()

// start the prediction
efficientV2Unet.process()
println("Script done")