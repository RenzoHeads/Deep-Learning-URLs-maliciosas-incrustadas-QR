# Cuestionario SUS (System Usability Scale)

## Instrucciones
Responda cada pregunta en una escala de 1 a 5, donde:
- 1 = Totalmente en desacuerdo
- 2 = En desacuerdo
- 3 = Neutral
- 4 = De acuerdo
- 5 = Totalmente de acuerdo

## Preguntas SUS (10 items)

| # | Pregunta | Tipo |
|---|----------|------|
| Q1 | Creo que me gustaria usar esta aplicacion frecuentemente | Positiva |
| Q2 | Encontre la aplicacion innecesariamente compleja | Negativa |
| Q3 | Pense que la aplicacion era facil de usar | Positiva |
| Q4 | Creo que necesitaria el apoyo de un tecnico para poder usar esta aplicacion | Negativa |
| Q5 | Encontre las diversas funciones de la aplicacion bien integradas | Positiva |
| Q6 | Pense que habia demasiadas inconsistencias en la aplicacion | Negativa |
| Q7 | Imaginaria que la mayoria de la gente aprenderia a usar esta aplicacion rapidamente | Positiva |
| Q8 | Encontre la aplicacion muy incmoda para usar | Negativa |
| Q9 | Me senti muy seguro usando la aplicacion | Positiva |
| Q10 | Necesite aprender muchas cosas antes de poder usar la aplicacion | Negativa |

## Formato CSV de respuestas

```
evaluador,q1,q2,q3,q4,q5,q6,q7,q8,q9,q10
E001,4,2,5,1,4,2,4,1,5,1
E002,5,1,4,2,5,1,5,1,4,2
```

## Calculo del SUS Score

1. Para items positivos (Q1, Q3, Q5, Q7, Q9): `punto = respuesta - 1`
2. Para items negativos (Q2, Q4, Q6, Q8, Q10): `punto = 5 - respuesta`
3. Sumar todos los puntos (X10 items) = 0 a 40
4. Multiplicar por 2.5 = Score SUS (0 a 100)

### Interpretacion

| SUS Score | Calificacion | Adjetivo |
|-----------|-------------|----------|
| > 80.3 | A | Excelente |
| 68-80.3 | B | Bueno |
| 51-68 | C | Regular |
| < 51 | D/F | Pobre |

**SUS > 68** = Usabilidad por encima del promedio (aceptable)
