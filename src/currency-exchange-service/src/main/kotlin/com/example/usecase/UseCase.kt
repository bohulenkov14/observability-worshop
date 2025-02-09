package com.example.usecase

interface UseCase<in I, out O> {
    fun execute(input: I): O
} 