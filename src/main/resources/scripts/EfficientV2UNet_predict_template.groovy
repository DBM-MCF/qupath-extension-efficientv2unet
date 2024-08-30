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
 * 4. Optionally, delete the exported raw image file (in the temp folder)
 * 5. Optionally, delete the predicted image file (in the Predict output folder)
 *
 */

def model_path = "/path/to/your/model/model_file.h5"
def efficientV2Unet = EfficientV2UNet.builder()
        // Modality settings
        .doPredict()                                            // either doTrain or doPredict must be true

        // Predict settings
        .setModelPath(model_path)                               // Path to the trained .h5 model file
//        .setTempDir("path/to/folder")                         // Defaults to "temp" inside your QuPath project
//        .setPredictOutputDirectory("path/to/another/folder")  // Defaults to "predictions" inside your QuPath project
        .setDownscale_factor(1)                                 // Image downscaling factor, at which to predict the image (1=no downscaling, 2=half resolution, ect.). Defaults to 1
        .setThreshold(0.5)                                      // Prediction probability threshold
        .setAnnotationClassName("Region")                       // Annotation class name for the detected objects. Defaults to "Region"
        .predictInSelection()                                   // If called, will predict the current selection. Only Rectangle selections are supported
        .splitObject()                                          // If called, will split the found predictions into separate objects
//        .removeExistingObjects()                              // If called, will remove ALL existing objects in the image
        .deleteTempFiles()                                      // If called, will delete the exported temporary raw image (in the Temp folder)
        .deletePredictionFiles()                                // If called, will delete the generated predicted image (in the Predict output folder)
        .build()

// Save the possible changes (e.g. if a ROI was drawn for prediction)
getProjectEntry().saveImageData(getCurrentImageData())
// start the prediction
def imageData = getCurrentImageData()
def selection = getSelectedObject()
if (selection != null) selection = selection.getROI()
else selection = null

// For script batching, using only a selection for prediction you can use something like that:
//if (getAnnotationObjects().size() > 0) selection = getAnnotationObjects().get(0).getROI()

efficientV2Unet.process(imageData, selection)
println("Script done")