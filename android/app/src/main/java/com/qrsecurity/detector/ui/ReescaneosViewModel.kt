package com.qrsecurity.detector.ui

/**
 * F2.5: typealias a [EstadoAnalisisAnteriores]. El VM heredado
 * [ReescaneosViewModel] se renombro a [AnalisisAnterioresViewModel];
 * este archivo mantiene los nombres viejos como aliases para que
 * [ReescaneosScreen] (que F3 reescribe) y el resto del codigo heredado
 * sigan compilando.
 */
typealias EstadoReescaneos = EstadoAnalisisAnteriores

/**
 * F2.5: typealias a [AnalisisAnterioresViewModel]. Mantiene la
 * compatibilidad con [ReescaneosScreen] y [NavGuardian.kt] (campo
 * `reescaneosViewModel`) mientras F3 los reescribe.
 *
 * El VM real vive en [AnalisisAnterioresViewModel]; este alias apunta
 * al mismo tipo, asi que `hiltViewModel<ReescaneosViewModel>()` y
 * `hiltViewModel<AnalisisAnterioresViewModel>()` devuelven la misma
 * instancia Hilt (mismo `@HiltViewModel`).
 */
typealias ReescaneosViewModel = AnalisisAnterioresViewModel
