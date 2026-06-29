import { NestFactory } from '@nestjs/core';
import { ValidationPipe } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { AppModule } from './app.module';
import { AllExceptionsFilter } from './common/all-exceptions.filter';

async function bootstrap() {
  const app = await NestFactory.create(AppModule, { bufferLogs: true });

  app.setGlobalPrefix('api/v1');
  app.enableCors({ origin: true, credentials: true });
  app.useGlobalPipes(
    new ValidationPipe({ whitelist: true, transform: true, forbidNonWhitelisted: true }),
  );
  app.useGlobalFilters(new AllExceptionsFilter());

  // OpenAPI / Swagger
  const config = new DocumentBuilder()
    .setTitle('CodeSpace IDE API')
    .setDescription('Backend services for CodeSpace IDE Mobile')
    .setVersion('1.0')
    .addBearerAuth()
    .build();
  const document = SwaggerModule.createDocument(app, config);
  SwaggerModule.setup('api/docs', app, document);

  const port = process.env.PORT ?? 8080;
  // Simple health check for Railway / load balancers
  app.use('/api/v1/health', (_req: any, res: any) => res.json({ status: 'ok' }));

  await app.listen(port);
  // eslint-disable-next-line no-console
  console.log(`CodeSpace API listening on :${port}`);
}
bootstrap();
