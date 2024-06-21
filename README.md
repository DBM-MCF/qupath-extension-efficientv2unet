# QuPath Efficient V2 UNet extension

Welcome to the Efficient V2 UNet extension for [QuPath](http://qupath.github.io)!

This adds support for running the Efficient V2 UNet implementation of [EfficientNetV2](https://arxiv.org/abs/2104.00298), to train model and segment histology images.

The current version is written for QuPath v0.5.0, and requires also the [qupath-cellpose-extension](https://github.com/BIOP/qupath-extension-cellpose) (v.0.9.3).

# Citing

If you are using this extension please cite following publications and repositories:

Mingxing T., Quoc V. Le **EfficientNetV2: Smaller Models and Faster Training**. arXiv (2021). https://doi.org/10.48550/arXiv.2104.00298

Bankhead, P. et al. **QuPath: Open source software for digital pathology image analysis**. Scientific Reports (2017). https://doi.org/10.1038/s41598-017-17204-5

The **QuPath cellpose extension** by Burri, O., Chiaruttini, N., and Guiet R. [GitHubRepo](https://github.com/BIOP/qupath-extension-cellpose) and [![DOI](https://zenodo.org/badge/417468733.svg)](https://zenodo.org/doi/10.5281/zenodo.10829243).

And this extension by linking to this GitHub repository and the [Efficient V2 UNet repository](https://github.com/DBM-MCF/efficientv2-unet).

## Code authorship

**Author**: Loïc Sauteur

**Affiliation**: Department of Biomedicine, University of Basel

# Installation
### Step 1: Install Efficient V2 UNet
- Create a python environment for the Efficient V2 UNet as described [here](https://github.com/DBM-MCF/efficientv2-unet).
### Step 2: Install the extension
- Download the latest `qupath-extension-efficientv2unet-[version].jar` file from releases, and add it into your extensions' directory.
   - If your extensions directory is unset, drag & drop `qupath-extension-efficientv2unet-[version].jar` onto the main QuPath window. 
     You'll be prompted to select a QuPath user directory.
     The extension will then be copied to a location inside that directory.
- Download the latest `qupath-extension-cellpose-[version].jar` from the respective [repository](https://github.com/BIOP/qupath-extension-cellpose), 
and add it into your extensions' directory.
 
### Set up the extension in QuPath
- In QuPath go to `Edit > Preferences > EfficientV2UNet`, 
specify the path to the EfficientV2UNet environment python executable file.
  - You can find the location of the python executable file by starting a CLI of the EffiecientV2UNet and
  typing `where python` (Windows) or `which python` (UNIX).
- Specify also the kind of environment used (see note below)
- You may also configure the cellpose settings (follow the [instructions](https://github.com/BIOP/qupath-extension-cellpose) accordingly), 
- but it is not required for this extension

> [!NOTE]
> `Python Executable` will work across general platforms. For OSX platforms it will be the default.
> 
> On Windows with a conda EfficientV2UNet, I suggest using `Anaconda or Miniconda` accordingly, as it will allow you to make use of the GPU.

# Usage
### Training
You can use annotated data in a QuPath project to train an EfficientV2UNet, 
using the menu entry: `Extensions > Efficient V2 UNet > Train a UNet`.

Follow the on-screen dialog to set it up.
Once set up, it will export images and mask, then perform the training.
- The `Only export image / mask pairs` option will allow you to export the images and corresponding masks, while skipping the training.
- Setting an Annotation class for `Region for image cropping` will use the corresponding annotations 
in the images to crop sub-parts of the image. If not specified (or not available in an image) it will use the full image.
- The `Foreground Annotation class` are the annotations that will be considered as positive pixels. 
Only images containing such annotations are selectable in the GUI.
- Select the images you want to use for generating training data and training (opened images should be saved before running the training)
- Select the base-model for training a new Efficient V2 UNet
  - options are `B0`, `B1`, `B2`, `B3`, `S` ,`M` and `L`, the first being the smallest and the last being the biggest.
- Specify the number of epochs you want to train your model for.
  - A starting point epochs = 50, but more is usually better.
  - Training a model, will also save the best-checkpoint model (with the best binary IoU metrics)

Similarly, you can use the provided script template in `Extensions > Efficient V2 UNet > Script templates > EV2UNet training script template` with annotation data already organised.

> [!NOTE]
> - For training, at least 3 images are required. The more, the better. Try also including variability between images.
> - Training images can have arbitrary sizes.

### Predicting
Using the menu entry: `Extensions > Efficient V2 UNet > Predict images`, you can predict images with a trained Efficient V2 UNet model.

Follow the on-screen dialog:
- Choose your model `*.h5` file 
> [!NOTE]
> It will indicate the best threshold and resolution to use, based on the metrics for test images the training has used. 
- Predicted annotation objects will be set to the selected class `Assing to class`
- Using the `Split Annotations` option will create individual objects from the prediction, rather than keeping separate objects as a single annotation.
- The `Remove existing Objects` **will delete all objects** (Annotations, Detections, Cells) in the image before adding the newly predicted ones.
- Adjust the `Threshold` according to the model metrics
- Adjust the `Inference resolution` according to the model metrics (downscaling of the image is performed by the python library not QuPath)
- Select which images to predict (opened images should be saved before running the prediction)

Similarly, you can use the provided script template in `Extensions > Efficient V2 UNet > Script templates > EV2UNet predict script template` to predict a currently opened project image.

### Additional utility
The menu entry: `Extensions > Efficient V2 UNet > Load a mask image` allows you to import a binary segmentation to one of your project images.

It requires both images to be the same size, and the mask image to be binary (0 = background, 1 = foreground).


# Building

You can build the QuPath Efficient V2 UNet extension from source with

```bash
gradlew clean build
```

The output will be under `build/libs`.

* `clean` removes anything old
* `build` builds the QuPath extension as a *.jar* file and adds it to `libs`
