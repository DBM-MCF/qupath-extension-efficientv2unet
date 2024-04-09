import qupath.ext.efficientv2unet.EfficientV2UNet


/**
 * Efficient V2 UNet template script to train a model
 * @author Loïc Sauteur
 *
 * This script will train a model using image-mask paris that have been saved to respective folders.
 * After defining the builder, train and save the model.
 * Basically doing the same as via the command line
 *
 * Notes:
 * - Training on apple silicone may not work
 * - To generate training data you can also use "Train a Efficient V2 UNet" function with the
 *      "Only export image / mask pairs" option active.
 */

def efficientV2Unet = EfficientV2UNet.builder()
        // Modality settings
        .doTrain(true)                                         // either doTrain or doPredict must be true
        // Train settings
        .setTrainImageDirectory("/path/to/image/dir")           // Directory where the images are located (must be existing folder with tif files)
        .setTrainMaskDirectory("path/to/mask/dir")              // Directory where the masks are located (must be existing folder with tif files)
//        .setBaseDirectory("path/to/model/saving/dir")           // Saving path of the model: defaults to "../YourQuPathProjectFolder/models"
        .setBasemodel("b0")                                     // Basemodel to use. Defaults to "b0". Others: b1, b2, b3, s, m, l
        .setName("myEfficientV2UNet_model")                     // Name for the model. Defaults to "myEfficientV2UNet_*basemodel*"
        .setEpochs(100)                                         // Number of epochs. Defaults to 100
        .build()

// start the training
efficientV2Unet.process()
println("Script done")