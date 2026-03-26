# 1. Usamos Eclipse Temurin (la versión más estable de Java hoy día)
FROM eclipse-temurin:21-jdk

# 2. Creamos carpeta
WORKDIR /app

# 3. Copiamos el código
COPY App.java .

# 4. Compilamos
RUN javac App.java

# ... (todo lo anterior igual)

# IMPORTANTE: Abrimos el puerto 8080 para que entre tráfico web
EXPOSE 8080

CMD ["java", "App"]