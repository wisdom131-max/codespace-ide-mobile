import { Injectable } from '@nestjs/common';
import { InjectRepository } from '@nestjs/typeorm';
import { Repository } from 'typeorm';
import { User } from './user.entity';

@Injectable()
export class UsersService {
  constructor(@InjectRepository(User) private readonly repo: Repository<User>) {}

  findByEmail(email: string) {
    return this.repo.findOne({ where: { email } });
  }

  findById(id: string) {
    return this.repo.findOne({ where: { id } });
  }

  findByFirebaseUid(firebaseUid: string) {
    return this.repo.findOne({ where: { firebaseUid } });
  }

  create(data: Partial<User>) {
    return this.repo.save(this.repo.create(data));
  }

  update(id: string, data: Partial<User>) {
    return this.repo.save({ id, ...data });
  }
}
