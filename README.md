# core-exts

This library only provides the core components for implementing / load external plugins to access specific content. It can be used in JVM and Android applications.

[![](https://jitpack.io/v/UsagiApp/core-exts.svg)](https://jitpack.io/#UsagiApp/core-exts) ![License](https://img.shields.io/github/license/UsagiApp/core-exts)

## Usage

1. Add it to your root `build.gradle` at the end of repositories:

   ```groovy
   allprojects {
       repositories {
           ...
           maven { url 'https://jitpack.io' }
       }
   }
   ```

2. Add the dependency (in your Java / Kotlin / Android project)

    ```groovy
    dependencies {
       implementation("com.github.UsagiApp:core-exts:$version")
    }
    ```

    Versions are available on [JitPack](https://jitpack.io/#UsagiApp/core-exts)

    When used in Android
    projects, [core library desugaring](https://developer.android.com/studio/write/java8-support#library-desugaring) with
    the [NIO specification](https://developer.android.com/studio/write/java11-nio-support-table) should be enabled to support Java 8+ features.


3. Usage in code

    **TODO**

## License

[![GNU GPLv3 Image](https://www.gnu.org/graphics/gplv3-127x51.png)](http://www.gnu.org/licenses/gpl-3.0.en.html)

<div align="left">

You may copy, distribute and modify the software as long as you track changes/dates in source files. Any modifications
to or software including (via compiler) GPL-licensed code must also be made available under the GPL along with build &
install instructions. See [LICENSE](./LICENSE) for more details.

</div>
