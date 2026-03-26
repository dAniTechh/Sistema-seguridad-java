# 1. Usamos Eclipse Temurin (la versión más estable de Java hoy día)
FROM eclipse-temurin:21-jdk

# 2. Creamos carpeta
WORKDIR /app

# 3. Copiamos el código
COPY App.java .

# 4. Compilamos
RUN javac App.java

# 5. Ejecutamos
EXPOSE 8080

CMD ["java", "App"]