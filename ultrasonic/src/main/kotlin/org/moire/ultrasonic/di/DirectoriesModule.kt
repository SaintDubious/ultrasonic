package org.moire.ultrasonic.di

import org.koin.dsl.bind
import org.koin.dsl.module
import org.moire.ultrasonic.cache.AndroidDirectories
import org.moire.ultrasonic.cache.Directories

/**
 * This Koin module contains the registration for Directories
 */
val directoriesModule = module {
    single { AndroidDirectories(get()) } bind Directories::class
}
