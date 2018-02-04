# Kfilter

Kfilter is a small image processing library. Kfilter aims to provide a simple interface to create photo and video filters as you would find in applications such as Snapchat or Instagram. 

![Example GIF](.resources/kfilter_sample_1.gif)

### Why use Kfilter?

* One filter system that works for both photos and videos
* Preview a filter before applying, including swipe to select between multiple filters
* Use a simple builder interface to create filters
* Or write your own GLSL fragment shader to create *any* filter you can imagine

### Why the "K"? 

Kfilter get's it's "K" from Kotlin. Kfilter was written in Kotlin, and is a Kotlin first library. You'll be able to use it with Java, but you'll have a lot more `fun` if you use Kotlin!

## Upcoming Features

* Improved SimpleKfilter builder more options
* Advanced node base Kfilter builder modelled after node based shader editors
* Built in media controls for videos
* A fully fleshed out sample app

## Download

Kfilter uses [JitPack.io](www.jitpack.io) to deploy dependencies. To use Kfilter, add the following to your project and module `build.gradle` files: 
```
allprojects {
	repositories {
		jcenter()
		maven { url "https://jitpack.io" }
	}
}
```
```
dependencies {
	compile 'com.github.isaac-udy:Kfilter:1.0.2'
}
```

### Special Thanks
This library would not have been possible without the awesome resources provided by [Grafika](https://github.com/google/grafika) and the [Android Media Codec CTS Tests](http://bigflake.com/mediacodec/). 

The [Instagram_Filter](https://github.com/yulu/Instagram_Filter) library also served as a source for some nicer looking default filters. 

### License

Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at

<http://www.apache.org/licenses/LICENSE-2.0>

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.