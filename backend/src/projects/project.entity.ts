import {
  Column, CreateDateColumn, Entity,
  ManyToOne, PrimaryColumn, UpdateDateColumn,
} from 'typeorm';
import { User } from '../users/user.entity';

export enum ProjectKind {
  LOCAL     = 'LOCAL',
  GIT       = 'GIT',
  CONTAINER = 'CONTAINER',
}

@Entity('projects')
export class Project {
  @PrimaryColumn()
  id: string;                   // client-generated (timestamp string)

  @Column()
  name: string;

  @Column({ type: 'varchar', default: ProjectKind.LOCAL })
  kind: ProjectKind;

  @Column({ default: 'local' })
  pathOrUrl: string;

  @Column({ nullable: true })
  defaultBranch?: string;

  @ManyToOne(() => User, { onDelete: 'CASCADE', nullable: false })
  owner: User;

  @Column()
  ownerId: string;              // FK shortcut for queries

  @CreateDateColumn()
  createdAt: Date;

  @UpdateDateColumn()
  updatedAt: Date;
}
